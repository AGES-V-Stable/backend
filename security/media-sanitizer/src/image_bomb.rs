use std::io::Read;

use flate2::read::{DeflateDecoder, ZlibDecoder};

use crate::{MediaKind, SanitizationError, SanitizationReport, SecurityPolicy};

const PNG_SIGNATURE: [u8; 8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
const ZIP_LOCAL_HEADER_SIG: [u8; 4] = [0x50, 0x4B, 0x03, 0x04];
const ZIP_CENTRAL_DIR_SIG: [u8; 4] = [0x50, 0x4B, 0x01, 0x02];
const ZIP_EOCD_SIG: [u8; 4] = [0x50, 0x4B, 0x05, 0x06];

const PNG_IDAT_MIN_RATIO_THRESHOLD: u64 = 4 * 1024;
const PNG_IDAT_SCANLINE_TOLERANCE: u64 = 4 * 1024;
const PNG_METADATA_MAX_BYTES: usize = 64 * 1024;
const PNG_METADATA_RATIO_THRESHOLD: u64 = 1024;
const ZIP_RATIO_THRESHOLD: u64 = 1024;
const STREAM_CHUNK_SIZE: usize = 8 * 1024;

#[derive(Debug, Clone, Copy)]
struct PngHeader {
    width: u32,
    height: u32,
    bit_depth: u8,
    color_type: u8,
    interlace_method: u8,
    channels: u64,
}

#[derive(Debug)]
struct ZipCentralEntry {
    name: String,
    flags: u16,
    method: u16,
    crc32: u32,
    compressed_size: u32,
    uncompressed_size: u32,
    local_header_offset: u32,
}

#[derive(Debug, Clone, Copy)]
struct ZipEocd {
    offset: usize,
    total_entries: usize,
    cd_size: usize,
    cd_offset: usize,
}

#[derive(Debug, Clone, Copy)]
struct LocalFileSpan {
    data_start: usize,
    data_end: usize,
    comp_size: u32,
    uncomp_size: u32,
    crc32: u32,
}

/// Verifica estrutura PNG, integridade CRC-32, dimensões em `IHDR` e payloads `IDAT`/`zTXt`/`iTXt`
/// contra pixel floods e bombas de descompressão zlib.
pub fn verify_png(
    bytes: &[u8],
    policy: &SecurityPolicy,
) -> Result<SanitizationReport, SanitizationError> {
    check_input_bounds(bytes, policy)?;

    if !bytes.starts_with(&PNG_SIGNATURE) {
        return Err(SanitizationError::InvalidFormat(
            "missing or invalid PNG signature".to_string(),
        ));
    }

    let mut offset = PNG_SIGNATURE.len();
    let mut header: Option<PngHeader> = None;
    let mut idat_payload = Vec::new();
    let mut total_metadata_decompressed: usize = 0;
    let mut saw_iend = false;

    while offset < bytes.len() {
        let remaining = bytes.len() - offset;
        if remaining < 12 {
            return Err(SanitizationError::InvalidFormat(
                "truncated PNG chunk header".to_string(),
            ));
        }

        let length = u32::from_be_bytes([
            bytes[offset],
            bytes[offset + 1],
            bytes[offset + 2],
            bytes[offset + 3],
        ]) as usize;

        if length > 0x7FFF_FFFF {
            return Err(SanitizationError::InvalidFormat(
                "PNG chunk length exceeds specification limit".to_string(),
            ));
        }

        let chunk_end = offset
            .checked_add(12)
            .and_then(|v| v.checked_add(length))
            .ok_or_else(|| SanitizationError::InvalidFormat("PNG chunk length overflow".into()))?;

        if chunk_end > bytes.len() {
            return Err(SanitizationError::InvalidFormat(
                "truncated PNG chunk payload".to_string(),
            ));
        }

        let type_start = offset + 4;
        let data_start = offset + 8;
        let data_end = data_start + length;
        let chunk_type = &bytes[type_start..data_start];

        if !chunk_type.iter().all(u8::is_ascii_alphabetic) {
            return Err(SanitizationError::InvalidFormat(
                "non-alphabetic PNG chunk type".to_string(),
            ));
        }

        let expected_crc = u32::from_be_bytes([
            bytes[data_end],
            bytes[data_end + 1],
            bytes[data_end + 2],
            bytes[data_end + 3],
        ]);
        let actual_crc = crc32fast::hash(&bytes[type_start..data_end]);
        if actual_crc != expected_crc {
            let chunk_name = std::str::from_utf8(chunk_type).unwrap_or("????");
            return Err(SanitizationError::InvalidFormat(format!(
                "CRC-32 mismatch in PNG chunk '{chunk_name}'"
            )));
        }

        let chunk_data = &bytes[data_start..data_end];
        offset = chunk_end;

        if header.is_none() {
            if chunk_type != b"IHDR" {
                return Err(SanitizationError::InvalidFormat(
                    "first PNG chunk must be IHDR".to_string(),
                ));
            }
            header = Some(parse_and_validate_ihdr(chunk_data, policy)?);
            continue;
        }

        match chunk_type {
            b"IHDR" => {
                return Err(SanitizationError::InvalidFormat(
                    "duplicate IHDR chunk in PNG".to_string(),
                ));
            }
            b"IDAT" => {
                let next_len = idat_payload
                    .len()
                    .checked_add(chunk_data.len())
                    .ok_or_else(|| {
                        SanitizationError::DecompressionBombDetected(
                            "PNG IDAT total size overflow".to_string(),
                        )
                    })?;
                if next_len > policy.max_decompressed_bytes {
                    return Err(SanitizationError::DecompressionBombDetected(
                        "PNG IDAT compressed payload exceeds budget".to_string(),
                    ));
                }
                idat_payload.extend_from_slice(chunk_data);
            }
            b"zTXt" => {
                let decompressed = verify_png_ztxt(chunk_data, policy)?;
                total_metadata_decompressed = total_metadata_decompressed
                    .checked_add(decompressed)
                    .ok_or_else(|| {
                        SanitizationError::DecompressionBombDetected(
                            "PNG metadata size overflow".to_string(),
                        )
                    })?;
                if total_metadata_decompressed > policy.max_decompressed_bytes {
                    return Err(SanitizationError::DecompressionBombDetected(
                        "PNG cumulative metadata exceeds max_decompressed_bytes".to_string(),
                    ));
                }
            }
            b"iTXt" => {
                let decompressed = verify_png_itxt(chunk_data, policy)?;
                total_metadata_decompressed = total_metadata_decompressed
                    .checked_add(decompressed)
                    .ok_or_else(|| {
                        SanitizationError::DecompressionBombDetected(
                            "PNG metadata size overflow".to_string(),
                        )
                    })?;
                if total_metadata_decompressed > policy.max_decompressed_bytes {
                    return Err(SanitizationError::DecompressionBombDetected(
                        "PNG cumulative metadata exceeds max_decompressed_bytes".to_string(),
                    ));
                }
            }
            b"IEND" => {
                if !chunk_data.is_empty() {
                    return Err(SanitizationError::InvalidFormat(
                        "PNG IEND chunk must be empty".to_string(),
                    ));
                }
                saw_iend = true;
                break;
            }
            _ => {}
        }
    }

    let hdr = header.ok_or_else(|| {
        SanitizationError::InvalidFormat("truncated PNG: missing IHDR chunk".to_string())
    })?;

    if !saw_iend {
        return Err(SanitizationError::InvalidFormat(
            "truncated PNG: missing IEND chunk".to_string(),
        ));
    }

    if offset != bytes.len() {
        return Err(SanitizationError::InvalidFormat(
            "trailing bytes found after PNG IEND chunk".to_string(),
        ));
    }

    if idat_payload.is_empty() {
        return Err(SanitizationError::InvalidFormat(
            "PNG contains no IDAT image data".to_string(),
        ));
    }

    let expected_scanlines = compute_png_scanline_bytes(&hdr)?;
    let remaining_budget = policy
        .max_decompressed_bytes
        .saturating_sub(total_metadata_decompressed);
    let scanline_cap = expected_scanlines.saturating_add(PNG_IDAT_SCANLINE_TOLERANCE);
    let idat_limit = scanline_cap.min(remaining_budget as u64);

    let decompressed_idat = decompress_zlib_checked(
        &idat_payload,
        idat_limit,
        policy.max_compression_ratio,
        PNG_IDAT_MIN_RATIO_THRESHOLD,
        "PNG IDAT stream",
    )?;

    Ok(SanitizationReport {
        kind: MediaKind::Png,
        input_bytes: bytes.len(),
        summary: format!(
            "valid PNG {}x{} (bit_depth={}, color_type={}, idat_decompressed={}B)",
            hdr.width, hdr.height, hdr.bit_depth, hdr.color_type, decompressed_idat
        ),
    })
}

/// Verifica estrutura JPEG (`SOI`, segmentos e `EOI`) e valida dimensões no marcador `SOF`
/// contra ataques de pixel flood.
pub fn verify_jpeg(
    bytes: &[u8],
    policy: &SecurityPolicy,
) -> Result<SanitizationReport, SanitizationError> {
    check_input_bounds(bytes, policy)?;

    if bytes.len() < 4 || !bytes.starts_with(&[0xFF, 0xD8]) {
        return Err(SanitizationError::InvalidFormat(
            "missing or truncated JPEG SOI header".to_string(),
        ));
    }

    let mut pos = 2;
    let mut dimensions: Option<(u32, u32, u8)> = None;
    let mut saw_eoi = false;

    while pos < bytes.len() {
        if bytes[pos] != 0xFF {
            return Err(SanitizationError::InvalidFormat(format!(
                "expected JPEG marker prefix 0xFF at offset {pos}"
            )));
        }

        while pos < bytes.len() && bytes[pos] == 0xFF {
            pos += 1;
        }
        if pos >= bytes.len() {
            return Err(SanitizationError::InvalidFormat(
                "truncated JPEG marker".to_string(),
            ));
        }

        let marker = bytes[pos];
        pos += 1;

        match marker {
            0x00 | 0xD8 => {
                return Err(SanitizationError::InvalidFormat(format!(
                    "unexpected JPEG marker 0x{marker:02X}"
                )));
            }
            0xD9 => {
                saw_eoi = true;
                break;
            }
            0x01 | 0xD0..=0xD7 => {
                // Marcadores standalone sem payload
            }
            _ => {
                if pos + 2 > bytes.len() {
                    return Err(SanitizationError::InvalidFormat(
                        "truncated JPEG segment length".to_string(),
                    ));
                }
                let seg_len = u16::from_be_bytes([bytes[pos], bytes[pos + 1]]) as usize;
                if seg_len < 2 || pos + seg_len > bytes.len() {
                    return Err(SanitizationError::InvalidFormat(
                        "invalid or truncated JPEG segment".to_string(),
                    ));
                }

                let seg_data = &bytes[pos + 2..pos + seg_len];
                if is_jpeg_sof_marker(marker) {
                    let (w, h) = parse_and_validate_jpeg_sof(seg_data, policy)?;
                    dimensions = Some((w, h, marker));
                }

                pos += seg_len;

                if marker == 0xDA {
                    pos = skip_jpeg_entropy_scan(bytes, pos)?;
                }
            }
        }
    }

    if !saw_eoi {
        return Err(SanitizationError::InvalidFormat(
            "truncated JPEG: missing EOI marker".to_string(),
        ));
    }

    if pos != bytes.len() {
        return Err(SanitizationError::InvalidFormat(
            "trailing bytes found after JPEG EOI marker".to_string(),
        ));
    }

    let (width, height, sof_marker) = dimensions.ok_or_else(|| {
        SanitizationError::InvalidFormat("JPEG contains no valid SOF marker".to_string())
    })?;

    Ok(SanitizationReport {
        kind: MediaKind::Jpeg,
        input_bytes: bytes.len(),
        summary: format!("valid JPEG {width}x{height} (SOF=0x{sof_marker:02X})"),
    })
}

/// Verifica integridade de arquivo ZIP de imagens, bloqueando path traversal, arquivos aninhados,
/// zip bombs (incluindo headers falsificados) e imagens internas que violem a política.
pub fn verify_image_zip(
    bytes: &[u8],
    policy: &SecurityPolicy,
) -> Result<SanitizationReport, SanitizationError> {
    check_input_bounds(bytes, policy)?;

    let eocd = parse_zip_eocd(bytes)?;

    if eocd.total_entries == 0 {
        return Err(SanitizationError::InvalidFormat(
            "ZIP archive contains no entries".to_string(),
        ));
    }

    if eocd.total_entries > policy.max_zip_entries {
        return Err(SanitizationError::DecompressionBombDetected(format!(
            "ZIP entry count {} exceeds maximum {}",
            eocd.total_entries, policy.max_zip_entries
        )));
    }

    let cd_end = eocd
        .cd_offset
        .checked_add(eocd.cd_size)
        .ok_or_else(|| SanitizationError::InvalidFormat("ZIP central directory overflow".into()))?;
    if cd_end > eocd.offset {
        return Err(SanitizationError::InvalidFormat(
            "ZIP central directory extends past EOCD".to_string(),
        ));
    }

    let entries =
        parse_zip_central_directory(bytes, eocd.cd_offset, cd_end, eocd.total_entries)?;
    let mut occupied_ranges: Vec<(usize, usize)> = Vec::with_capacity(entries.len());
    let mut total_decompressed: usize = 0;

    for entry in &entries {
        validate_zip_entry_name(&entry.name)?;

        let span = validate_local_file_header(bytes, entry, eocd.cd_offset)?;

        let record_start = entry.local_header_offset as usize;
        if occupied_ranges
            .iter()
            .any(|&(s, e)| record_start < e && span.data_end > s)
        {
            return Err(SanitizationError::DecompressionBombDetected(format!(
                "overlapping ZIP entry data detected for '{}'",
                entry.name
            )));
        }
        occupied_ranges.push((record_start, span.data_end));

        let remaining_budget = policy
            .max_decompressed_bytes
            .saturating_sub(total_decompressed);
        validate_declared_zip_sizes(
            &entry.name,
            span.comp_size,
            span.uncomp_size,
            remaining_budget,
            policy.max_compression_ratio,
        )?;

        let compressed_slice = &bytes[span.data_start..span.data_end];
        let decompressed = decompress_zip_entry(
            &entry.name,
            entry.method,
            compressed_slice,
            u64::from(span.uncomp_size),
            remaining_budget,
            policy.max_compression_ratio,
        )?;

        let actual_crc = crc32fast::hash(&decompressed);
        if actual_crc != span.crc32 {
            return Err(SanitizationError::InvalidFormat(format!(
                "CRC-32 mismatch in ZIP entry '{}'",
                entry.name
            )));
        }

        total_decompressed = total_decompressed
            .checked_add(decompressed.len())
            .ok_or_else(|| {
                SanitizationError::DecompressionBombDetected(
                    "ZIP cumulative decompressed size overflow".to_string(),
                )
            })?;

        if has_nested_archive_magic(&decompressed) {
            return Err(SanitizationError::NestedArchiveDetected(entry.name.clone()));
        }

        validate_extracted_image(&entry.name, &decompressed, policy)?;
    }

    Ok(SanitizationReport {
        kind: MediaKind::Zip,
        input_bytes: bytes.len(),
        summary: format!(
            "valid image ZIP ({} entries, total_decompressed={}B)",
            entries.len(),
            total_decompressed
        ),
    })
}

fn check_input_bounds(bytes: &[u8], policy: &SecurityPolicy) -> Result<(), SanitizationError> {
    if bytes.is_empty() {
        return Err(SanitizationError::EmptyInput);
    }
    let max_allowed = policy.max_input_bytes.max(policy.max_decompressed_bytes);
    if bytes.len() > max_allowed {
        return Err(SanitizationError::InputTooLarge {
            actual: bytes.len(),
            max: max_allowed,
        });
    }
    Ok(())
}

fn parse_and_validate_ihdr(
    data: &[u8],
    policy: &SecurityPolicy,
) -> Result<PngHeader, SanitizationError> {
    if data.len() != 13 {
        return Err(SanitizationError::InvalidFormat(
            "PNG IHDR chunk must be 13 bytes".to_string(),
        ));
    }

    let width = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
    let height = u32::from_be_bytes([data[4], data[5], data[6], data[7]]);
    let bit_depth = data[8];
    let color_type = data[9];
    let compression_method = data[10];
    let filter_method = data[11];
    let interlace_method = data[12];

    if compression_method != 0 || filter_method != 0 || interlace_method > 1 {
        return Err(SanitizationError::InvalidFormat(
            "unsupported PNG IHDR compression/filter/interlace method".to_string(),
        ));
    }

    let channels = png_channels_for_format(color_type, bit_depth)?;

    if width == 0 || height == 0 {
        return Err(SanitizationError::InvalidFormat(
            "PNG width and height must be greater than zero".to_string(),
        ));
    }

    let pixels = u64::from(width) * u64::from(height);
    if width > policy.max_image_width
        || height > policy.max_image_height
        || pixels > policy.max_image_pixels
    {
        return Err(SanitizationError::ImageDimensionExceeded {
            width,
            height,
            pixels,
        });
    }

    Ok(PngHeader {
        width,
        height,
        bit_depth,
        color_type,
        interlace_method,
        channels,
    })
}

fn png_channels_for_format(color_type: u8, bit_depth: u8) -> Result<u64, SanitizationError> {
    match (color_type, bit_depth) {
        (0, 1 | 2 | 4 | 8 | 16) => Ok(1),
        (2, 8 | 16) => Ok(3),
        (3, 1 | 2 | 4 | 8) => Ok(1),
        (4, 8 | 16) => Ok(2),
        (6, 8 | 16) => Ok(4),
        _ => Err(SanitizationError::InvalidFormat(format!(
            "invalid PNG color_type={color_type} / bit_depth={bit_depth} combination"
        ))),
    }
}

fn compute_png_scanline_bytes(hdr: &PngHeader) -> Result<u64, SanitizationError> {
    let bpp = hdr
        .channels
        .checked_mul(u64::from(hdr.bit_depth))
        .ok_or_else(|| SanitizationError::InvalidFormat("PNG bpp overflow".to_string()))?;

    if hdr.interlace_method == 0 {
        let bits_per_row = u64::from(hdr.width)
            .checked_mul(bpp)
            .ok_or_else(|| SanitizationError::InvalidFormat("PNG row bits overflow".to_string()))?;
        let row_bytes = bits_per_row.div_ceil(8);
        return u64::from(hdr.height)
            .checked_mul(row_bytes.saturating_add(1))
            .ok_or_else(|| SanitizationError::InvalidFormat("PNG scanline size overflow".into()));
    }

    const ADAM7_PASSES: [(u32, u32, u32, u32); 7] = [
        (0, 8, 0, 8),
        (4, 8, 0, 8),
        (0, 4, 4, 8),
        (2, 4, 0, 4),
        (0, 2, 2, 4),
        (1, 2, 0, 2),
        (0, 1, 1, 2),
    ];

    let mut total: u64 = 0;
    for (x_off, x_step, y_off, y_step) in ADAM7_PASSES {
        if hdr.width <= x_off || hdr.height <= y_off {
            continue;
        }
        let pass_w = (hdr.width - 1 - x_off) / x_step + 1;
        let pass_h = (hdr.height - 1 - y_off) / y_step + 1;
        let row_bytes = (u64::from(pass_w) * bpp).div_ceil(8);
        let pass_bytes = u64::from(pass_h)
            .checked_mul(row_bytes.saturating_add(1))
            .ok_or_else(|| SanitizationError::InvalidFormat("PNG Adam7 size overflow".into()))?;
        total = total
            .checked_add(pass_bytes)
            .ok_or_else(|| SanitizationError::InvalidFormat("PNG Adam7 total overflow".into()))?;
    }

    Ok(total)
}

fn verify_png_ztxt(data: &[u8], policy: &SecurityPolicy) -> Result<usize, SanitizationError> {
    let nul_pos = data
        .iter()
        .position(|&b| b == 0)
        .ok_or_else(|| SanitizationError::InvalidFormat("PNG zTXt missing null separator".into()))?;

    if nul_pos == 0 || nul_pos > 79 || nul_pos + 2 > data.len() {
        return Err(SanitizationError::InvalidFormat(
            "malformed PNG zTXt chunk header".to_string(),
        ));
    }

    let compression_method = data[nul_pos + 1];
    if compression_method != 0 {
        return Err(SanitizationError::InvalidFormat(format!(
            "unsupported PNG zTXt compression method {compression_method}"
        )));
    }

    let compressed = &data[nul_pos + 2..];
    let cap = PNG_METADATA_MAX_BYTES.min(policy.max_decompressed_bytes) as u64;
    decompress_zlib_checked(
        compressed,
        cap,
        policy.max_compression_ratio,
        PNG_METADATA_RATIO_THRESHOLD,
        "PNG zTXt metadata",
    )
}

fn verify_png_itxt(data: &[u8], policy: &SecurityPolicy) -> Result<usize, SanitizationError> {
    let keyword_end = data
        .iter()
        .position(|&b| b == 0)
        .ok_or_else(|| SanitizationError::InvalidFormat("PNG iTXt missing null separator".into()))?;

    if keyword_end == 0 || keyword_end > 79 || keyword_end + 3 > data.len() {
        return Err(SanitizationError::InvalidFormat(
            "malformed PNG iTXt chunk header".to_string(),
        ));
    }

    let compression_flag = data[keyword_end + 1];
    let compression_method = data[keyword_end + 2];
    if compression_flag > 1 {
        return Err(SanitizationError::InvalidFormat(
            "invalid PNG iTXt compression flag".to_string(),
        ));
    }

    let rest = &data[keyword_end + 3..];
    let lang_end = rest.iter().position(|&b| b == 0).ok_or_else(|| {
        SanitizationError::InvalidFormat("PNG iTXt missing language terminator".into())
    })?;
    let after_lang = &rest[lang_end + 1..];
    let trans_end = after_lang.iter().position(|&b| b == 0).ok_or_else(|| {
        SanitizationError::InvalidFormat("PNG iTXt missing translated keyword terminator".into())
    })?;
    let text_payload = &after_lang[trans_end + 1..];

    if compression_flag == 0 {
        return Ok(text_payload.len());
    }

    if compression_method != 0 {
        return Err(SanitizationError::InvalidFormat(format!(
            "unsupported PNG iTXt compression method {compression_method}"
        )));
    }

    let cap = PNG_METADATA_MAX_BYTES.min(policy.max_decompressed_bytes) as u64;
    decompress_zlib_checked(
        text_payload,
        cap,
        policy.max_compression_ratio,
        PNG_METADATA_RATIO_THRESHOLD,
        "PNG iTXt metadata",
    )
}

fn decompress_zlib_checked(
    compressed: &[u8],
    max_decompressed: u64,
    max_ratio: u64,
    min_ratio_threshold: u64,
    context: &str,
) -> Result<usize, SanitizationError> {
    let mut decoder = ZlibDecoder::new(compressed).take(max_decompressed.saturating_add(1));
    let mut buf = [0u8; STREAM_CHUNK_SIZE];
    let mut total: u64 = 0;
    let compressed_len = (compressed.len() as u64).max(1);

    loop {
        let read_bytes = decoder.read(&mut buf).map_err(|err| {
            SanitizationError::InvalidFormat(format!("invalid zlib stream in {context}: {err}"))
        })?;
        if read_bytes == 0 {
            break;
        }
        total = total.saturating_add(read_bytes as u64);

        if total > max_decompressed {
            return Err(SanitizationError::DecompressionBombDetected(format!(
                "{context} exceeded limit of {max_decompressed} bytes"
            )));
        }

        if total > min_ratio_threshold && total > compressed_len.saturating_mul(max_ratio) {
            return Err(SanitizationError::DecompressionBombDetected(format!(
                "{context} compression ratio exceeded {max_ratio}:1 ({total}/{compressed_len})"
            )));
        }
    }

    usize::try_from(total).map_err(|_| {
        SanitizationError::DecompressionBombDetected(format!(
            "{context} decompressed length overflow"
        ))
    })
}

fn is_jpeg_sof_marker(marker: u8) -> bool {
    matches!(
        marker,
        0xC0..=0xC3 | 0xC5..=0xC7 | 0xC9..=0xCB | 0xCD..=0xCF
    )
}

fn parse_and_validate_jpeg_sof(
    seg_data: &[u8],
    policy: &SecurityPolicy,
) -> Result<(u32, u32), SanitizationError> {
    if seg_data.len() < 6 {
        return Err(SanitizationError::InvalidFormat(
            "truncated JPEG SOF segment".to_string(),
        ));
    }

    let height = u32::from(u16::from_be_bytes([seg_data[1], seg_data[2]]));
    let width = u32::from(u16::from_be_bytes([seg_data[3], seg_data[4]]));
    let components = usize::from(seg_data[5]);

    if components == 0 || seg_data.len() != 6 + components * 3 {
        return Err(SanitizationError::InvalidFormat(
            "invalid JPEG SOF component table length".to_string(),
        ));
    }

    if width == 0 || height == 0 {
        return Err(SanitizationError::InvalidFormat(
            "JPEG dimensions must be greater than zero".to_string(),
        ));
    }

    let pixels = u64::from(width) * u64::from(height);
    if width > policy.max_image_width
        || height > policy.max_image_height
        || pixels > policy.max_image_pixels
    {
        return Err(SanitizationError::ImageDimensionExceeded {
            width,
            height,
            pixels,
        });
    }

    Ok((width, height))
}

fn skip_jpeg_entropy_scan(bytes: &[u8], mut pos: usize) -> Result<usize, SanitizationError> {
    while pos < bytes.len() {
        if bytes[pos] != 0xFF {
            pos += 1;
            continue;
        }

        let mut next = pos + 1;
        while next < bytes.len() && bytes[next] == 0xFF {
            next += 1;
        }
        if next >= bytes.len() {
            return Err(SanitizationError::InvalidFormat(
                "truncated JPEG entropy-coded scan".to_string(),
            ));
        }

        let code = bytes[next];
        if code == 0x00 || (0xD0..=0xD7).contains(&code) {
            pos = next + 1;
        } else {
            return Ok(next - 1);
        }
    }

    Err(SanitizationError::InvalidFormat(
        "unterminated JPEG entropy-coded scan (missing EOI)".to_string(),
    ))
}

fn parse_zip_eocd(bytes: &[u8]) -> Result<ZipEocd, SanitizationError> {
    if bytes.len() < 22 {
        return Err(SanitizationError::InvalidFormat(
            "ZIP file too small for EOCD".to_string(),
        ));
    }

    let min_offset = bytes.len().saturating_sub(22 + 65_535);
    let max_offset = bytes.len() - 22;

    for off in (min_offset..=max_offset).rev() {
        if bytes[off..off + 4] != ZIP_EOCD_SIG {
            continue;
        }
        let comment_len = usize::from(u16::from_le_bytes([bytes[off + 20], bytes[off + 21]]));
        if off + 22 + comment_len != bytes.len() {
            continue;
        }

        let disk_num = u16::from_le_bytes([bytes[off + 4], bytes[off + 5]]);
        let cd_disk = u16::from_le_bytes([bytes[off + 6], bytes[off + 7]]);
        let disk_entries = u16::from_le_bytes([bytes[off + 8], bytes[off + 9]]);
        let total_entries = u16::from_le_bytes([bytes[off + 10], bytes[off + 11]]);
        let cd_size = u32::from_le_bytes([
            bytes[off + 12],
            bytes[off + 13],
            bytes[off + 14],
            bytes[off + 15],
        ]);
        let cd_offset = u32::from_le_bytes([
            bytes[off + 16],
            bytes[off + 17],
            bytes[off + 18],
            bytes[off + 19],
        ]);

        if disk_num != 0 || cd_disk != 0 || disk_entries != total_entries {
            return Err(SanitizationError::InvalidFormat(
                "multi-disk or inconsistent ZIP EOCD is unsupported".to_string(),
            ));
        }

        if total_entries == u16::MAX || cd_size == u32::MAX || cd_offset == u32::MAX {
            return Err(SanitizationError::DecompressionBombDetected(
                "ZIP64 sentinel values are not permitted".to_string(),
            ));
        }

        return Ok(ZipEocd {
            offset: off,
            total_entries: usize::from(total_entries),
            cd_size: cd_size as usize,
            cd_offset: cd_offset as usize,
        });
    }

    Err(SanitizationError::InvalidFormat(
        "missing or invalid ZIP EOCD record".to_string(),
    ))
}

fn parse_zip_central_directory(
    bytes: &[u8],
    cd_offset: usize,
    cd_end: usize,
    expected_entries: usize,
) -> Result<Vec<ZipCentralEntry>, SanitizationError> {
    let mut entries = Vec::with_capacity(expected_entries);
    let mut pos = cd_offset;

    for _ in 0..expected_entries {
        if pos + 46 > cd_end || bytes[pos..pos + 4] != ZIP_CENTRAL_DIR_SIG {
            return Err(SanitizationError::InvalidFormat(
                "invalid or truncated ZIP central directory entry".to_string(),
            ));
        }

        let flags = u16::from_le_bytes([bytes[pos + 8], bytes[pos + 9]]);
        let method = u16::from_le_bytes([bytes[pos + 10], bytes[pos + 11]]);
        let crc32 = u32::from_le_bytes([
            bytes[pos + 16],
            bytes[pos + 17],
            bytes[pos + 18],
            bytes[pos + 19],
        ]);
        let compressed_size = u32::from_le_bytes([
            bytes[pos + 20],
            bytes[pos + 21],
            bytes[pos + 22],
            bytes[pos + 23],
        ]);
        let uncompressed_size = u32::from_le_bytes([
            bytes[pos + 24],
            bytes[pos + 25],
            bytes[pos + 26],
            bytes[pos + 27],
        ]);
        let name_len = usize::from(u16::from_le_bytes([bytes[pos + 28], bytes[pos + 29]]));
        let extra_len = usize::from(u16::from_le_bytes([bytes[pos + 30], bytes[pos + 31]]));
        let comment_len = usize::from(u16::from_le_bytes([bytes[pos + 32], bytes[pos + 33]]));
        let local_header_offset = u32::from_le_bytes([
            bytes[pos + 42],
            bytes[pos + 43],
            bytes[pos + 44],
            bytes[pos + 45],
        ]);

        let entry_end = pos
            .checked_add(46)
            .and_then(|v| v.checked_add(name_len))
            .and_then(|v| v.checked_add(extra_len))
            .and_then(|v| v.checked_add(comment_len))
            .ok_or_else(|| SanitizationError::InvalidFormat("ZIP CD entry overflow".into()))?;

        if entry_end > cd_end {
            return Err(SanitizationError::InvalidFormat(
                "ZIP central directory entry exceeds CD bounds".to_string(),
            ));
        }

        let name = std::str::from_utf8(&bytes[pos + 46..pos + 46 + name_len])
            .map_err(|_| SanitizationError::InvalidFormat("non-UTF8 ZIP entry name".to_string()))?
            .to_string();

        entries.push(ZipCentralEntry {
            name,
            flags,
            method,
            crc32,
            compressed_size,
            uncompressed_size,
            local_header_offset,
        });

        pos = entry_end;
    }

    if pos != cd_end {
        return Err(SanitizationError::InvalidFormat(
            "ZIP central directory size mismatch".to_string(),
        ));
    }

    Ok(entries)
}

fn validate_zip_entry_name(name: &str) -> Result<(), SanitizationError> {
    if name.is_empty() {
        return Err(SanitizationError::InvalidFormat(
            "ZIP entry has empty filename".to_string(),
        ));
    }

    if name.contains("..")
        || name.contains('/')
        || name.contains('\\')
        || name.contains(':')
        || name.contains('\0')
    {
        return Err(SanitizationError::PathTraversalDetected(name.to_string()));
    }

    let lower = name.to_ascii_lowercase();
    if lower.ends_with(".zip")
        || lower.ends_with(".tar")
        || lower.ends_with(".gz")
        || lower.ends_with(".tgz")
        || lower.ends_with(".7z")
        || lower.ends_with(".rar")
        || lower.ends_with(".bz2")
        || lower.ends_with(".xz")
    {
        return Err(SanitizationError::NestedArchiveDetected(name.to_string()));
    }

    Ok(())
}

fn validate_local_file_header(
    bytes: &[u8],
    entry: &ZipCentralEntry,
    cd_offset: usize,
) -> Result<LocalFileSpan, SanitizationError> {
    let off = entry.local_header_offset as usize;
    if off + 30 > cd_offset || bytes[off..off + 4] != ZIP_LOCAL_HEADER_SIG {
        return Err(SanitizationError::InvalidFormat(format!(
            "invalid ZIP local file header for '{}'",
            entry.name
        )));
    }

    let lfh_flags = u16::from_le_bytes([bytes[off + 6], bytes[off + 7]]);
    let lfh_method = u16::from_le_bytes([bytes[off + 8], bytes[off + 9]]);
    let lfh_crc = u32::from_le_bytes([
        bytes[off + 14],
        bytes[off + 15],
        bytes[off + 16],
        bytes[off + 17],
    ]);
    let lfh_comp = u32::from_le_bytes([
        bytes[off + 18],
        bytes[off + 19],
        bytes[off + 20],
        bytes[off + 21],
    ]);
    let lfh_uncomp = u32::from_le_bytes([
        bytes[off + 22],
        bytes[off + 23],
        bytes[off + 24],
        bytes[off + 25],
    ]);
    let lfh_name_len = usize::from(u16::from_le_bytes([bytes[off + 26], bytes[off + 27]]));
    let lfh_extra_len = usize::from(u16::from_le_bytes([bytes[off + 28], bytes[off + 29]]));

    if (entry.flags & 0x0001) != 0 || (lfh_flags & 0x0001) != 0 {
        return Err(SanitizationError::InvalidFormat(format!(
            "encrypted ZIP entry '{}' is not permitted",
            entry.name
        )));
    }

    if lfh_method != entry.method {
        return Err(SanitizationError::InvalidFormat(format!(
            "ZIP compression method mismatch for '{}'",
            entry.name
        )));
    }

    let data_start = off
        .checked_add(30)
        .and_then(|v| v.checked_add(lfh_name_len))
        .and_then(|v| v.checked_add(lfh_extra_len))
        .ok_or_else(|| SanitizationError::InvalidFormat("ZIP LFH offset overflow".into()))?;

    if data_start > cd_offset {
        return Err(SanitizationError::InvalidFormat(format!(
            "ZIP local header extends past central directory for '{}'",
            entry.name
        )));
    }

    let lfh_name = &bytes[off + 30..off + 30 + lfh_name_len];
    if lfh_name != entry.name.as_bytes() {
        return Err(SanitizationError::InvalidFormat(format!(
            "ZIP LFH filename mismatch for '{}'",
            entry.name
        )));
    }

    // Se a flag de data descriptor (bit 3) estiver desligada, LFH e CD devem concordar exatamente.
    if (lfh_flags & 0x0008) == 0
        && (lfh_comp != entry.compressed_size
            || lfh_uncomp != entry.uncompressed_size
            || lfh_crc != entry.crc32)
    {
        return Err(SanitizationError::InvalidFormat(format!(
            "ZIP header metadata mismatch between LFH and CD for '{}'",
            entry.name
        )));
    }

    let data_end = data_start
        .checked_add(entry.compressed_size as usize)
        .ok_or_else(|| SanitizationError::InvalidFormat("ZIP entry data overflow".into()))?;

    if data_end > cd_offset {
        return Err(SanitizationError::InvalidFormat(format!(
            "truncated ZIP compressed payload for '{}'",
            entry.name
        )));
    }

    Ok(LocalFileSpan {
        data_start,
        data_end,
        comp_size: entry.compressed_size,
        uncomp_size: entry.uncompressed_size,
        crc32: entry.crc32,
    })
}

fn validate_declared_zip_sizes(
    name: &str,
    comp_size: u32,
    uncomp_size: u32,
    remaining_budget: usize,
    max_ratio: u64,
) -> Result<(), SanitizationError> {
    if (uncomp_size as usize) > remaining_budget {
        return Err(SanitizationError::DecompressionBombDetected(format!(
            "ZIP entry '{name}' declared size {uncomp_size} exceeds remaining budget {remaining_budget}"
        )));
    }

    if comp_size == 0 && uncomp_size > 0 {
        return Err(SanitizationError::DecompressionBombDetected(format!(
            "ZIP entry '{name}' declares {uncomp_size} bytes from 0 compressed bytes"
        )));
    }

    let uncomp_u64 = u64::from(uncomp_size);
    let comp_u64 = u64::from(comp_size);
    if uncomp_u64 > ZIP_RATIO_THRESHOLD && uncomp_u64 > comp_u64.saturating_mul(max_ratio) {
        return Err(SanitizationError::DecompressionBombDetected(format!(
            "ZIP entry '{name}' declared ratio exceeds {max_ratio}:1"
        )));
    }

    Ok(())
}

fn decompress_zip_entry(
    name: &str,
    method: u16,
    compressed: &[u8],
    declared_uncomp: u64,
    remaining_budget: usize,
    max_ratio: u64,
) -> Result<Vec<u8>, SanitizationError> {
    match method {
        0 => {
            if compressed.len() as u64 != declared_uncomp {
                return Err(SanitizationError::DecompressionBombDetected(format!(
                    "ZIP Store entry '{name}' length differs from declared header size"
                )));
            }
            if compressed.len() > remaining_budget {
                return Err(SanitizationError::DecompressionBombDetected(format!(
                    "ZIP Store entry '{name}' exceeds decompression budget"
                )));
            }
            Ok(compressed.to_vec())
        }
        8 => {
            let cap = (remaining_budget as u64).saturating_add(1);
            let mut decoder = DeflateDecoder::new(compressed).take(cap);
            let initial_cap = usize::try_from(declared_uncomp)
                .unwrap_or(STREAM_CHUNK_SIZE)
                .min(64 * 1024);
            let mut out = Vec::with_capacity(initial_cap);
            let mut buf = [0u8; STREAM_CHUNK_SIZE];
            let comp_len = (compressed.len() as u64).max(1);

            loop {
                let n = decoder.read(&mut buf).map_err(|err| {
                    SanitizationError::InvalidFormat(format!(
                        "invalid DEFLATE stream in ZIP entry '{name}': {err}"
                    ))
                })?;
                if n == 0 {
                    break;
                }
                if out.len() + n > remaining_budget {
                    return Err(SanitizationError::DecompressionBombDetected(format!(
                        "ZIP entry '{name}' exceeded decompressed budget {remaining_budget} bytes"
                    )));
                }
                out.extend_from_slice(&buf[..n]);

                let out_len = out.len() as u64;
                if out_len > ZIP_RATIO_THRESHOLD && out_len > comp_len.saturating_mul(max_ratio) {
                    return Err(SanitizationError::DecompressionBombDetected(format!(
                        "ZIP entry '{name}' actual compression ratio exceeded {max_ratio}:1"
                    )));
                }
            }

            if out.len() as u64 != declared_uncomp {
                return Err(SanitizationError::DecompressionBombDetected(format!(
                    "ZIP entry '{name}' spoofed header size (declared {declared_uncomp}, actual {})",
                    out.len()
                )));
            }

            Ok(out)
        }
        other => Err(SanitizationError::InvalidFormat(format!(
            "unsupported ZIP compression method {other} in '{name}'"
        ))),
    }
}

fn has_nested_archive_magic(data: &[u8]) -> bool {
    data.starts_with(&ZIP_LOCAL_HEADER_SIG)
        || data.starts_with(&ZIP_EOCD_SIG)
        || data.starts_with(&[0x1F, 0x8B])
        || data.starts_with(b"Rar!\x1A\x07")
        || data.starts_with(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C])
}

fn validate_extracted_image(
    name: &str,
    data: &[u8],
    policy: &SecurityPolicy,
) -> Result<(), SanitizationError> {
    if data.starts_with(&PNG_SIGNATURE) {
        verify_png(data, policy)?;
        Ok(())
    } else if data.starts_with(&[0xFF, 0xD8, 0xFF]) {
        verify_jpeg(data, policy)?;
        Ok(())
    } else {
        Err(SanitizationError::InvalidFormat(format!(
            "ZIP entry '{name}' is not a supported PNG or JPEG image"
        )))
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use flate2::write::{DeflateEncoder, ZlibEncoder};
    use flate2::Compression;

    use super::*;

    fn zlib_compress(data: &[u8]) -> Vec<u8> {
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::best());
        enc.write_all(data).expect("zlib write");
        enc.finish().expect("zlib finish")
    }

    fn deflate_compress(data: &[u8]) -> Vec<u8> {
        let mut enc = DeflateEncoder::new(Vec::new(), Compression::best());
        enc.write_all(data).expect("deflate write");
        enc.finish().expect("deflate finish")
    }

    fn push_png_chunk(out: &mut Vec<u8>, chunk_type: &[u8; 4], data: &[u8]) {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let type_start = out.len();
        out.extend_from_slice(chunk_type);
        out.extend_from_slice(data);
        let crc = crc32fast::hash(&out[type_start..]);
        out.extend_from_slice(&crc.to_be_bytes());
    }

    fn build_ihdr_data(width: u32, height: u32, bit_depth: u8, color_type: u8) -> [u8; 13] {
        let mut d = [0u8; 13];
        d[0..4].copy_from_slice(&width.to_be_bytes());
        d[4..8].copy_from_slice(&height.to_be_bytes());
        d[8] = bit_depth;
        d[9] = color_type;
        d
    }

    fn build_valid_png_2x2() -> Vec<u8> {
        let mut png = Vec::new();
        png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(&mut png, b"IHDR", &build_ihdr_data(2, 2, 8, 6));
        // 2 scanlines * (1 filter byte + 2 * 4 RGBA bytes) = 18 bytes
        let raw_scanlines = vec![0u8; 18];
        let idat = zlib_compress(&raw_scanlines);
        push_png_chunk(&mut png, b"IDAT", &idat);
        push_png_chunk(&mut png, b"IEND", &[]);
        png
    }

    fn build_minimal_jpeg(width: u16, height: u16) -> Vec<u8> {
        let mut jpeg = Vec::new();
        // SOI
        jpeg.extend_from_slice(&[0xFF, 0xD8]);
        // SOF0 (0xC0): length=17 (2 + 6 + 3*3 components)
        jpeg.extend_from_slice(&[0xFF, 0xC0, 0x00, 17, 8]);
        jpeg.extend_from_slice(&height.to_be_bytes());
        jpeg.extend_from_slice(&width.to_be_bytes());
        jpeg.push(3); // 3 components (YCbCr)
        jpeg.extend_from_slice(&[1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1]);
        // EOI
        jpeg.extend_from_slice(&[0xFF, 0xD9]);
        jpeg
    }

    struct ZipEntrySpec<'a> {
        name: &'a str,
        method: u16,
        compressed: Vec<u8>,
        declared_uncompressed: u32,
        crc32: u32,
    }

    fn build_zip(entries: &[ZipEntrySpec<'_>]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut cd = Vec::new();

        for e in entries {
            let lfh_offset = out.len() as u32;
            let name_bytes = e.name.as_bytes();
            let name_len = name_bytes.len() as u16;
            let comp_len = e.compressed.len() as u32;

            // Local file header (30 bytes + name)
            out.extend_from_slice(&ZIP_LOCAL_HEADER_SIG);
            out.extend_from_slice(&20u16.to_le_bytes()); // version needed
            out.extend_from_slice(&0u16.to_le_bytes()); // flags
            out.extend_from_slice(&e.method.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes()); // mod time
            out.extend_from_slice(&0u16.to_le_bytes()); // mod date
            out.extend_from_slice(&e.crc32.to_le_bytes());
            out.extend_from_slice(&comp_len.to_le_bytes());
            out.extend_from_slice(&e.declared_uncompressed.to_le_bytes());
            out.extend_from_slice(&name_len.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes()); // extra len
            out.extend_from_slice(name_bytes);
            out.extend_from_slice(&e.compressed);

            // Central directory header (46 bytes + name)
            cd.extend_from_slice(&ZIP_CENTRAL_DIR_SIG);
            cd.extend_from_slice(&20u16.to_le_bytes()); // version made by
            cd.extend_from_slice(&20u16.to_le_bytes()); // version needed
            cd.extend_from_slice(&0u16.to_le_bytes()); // flags
            cd.extend_from_slice(&e.method.to_le_bytes());
            cd.extend_from_slice(&0u16.to_le_bytes()); // mod time
            cd.extend_from_slice(&0u16.to_le_bytes()); // mod date
            cd.extend_from_slice(&e.crc32.to_le_bytes());
            cd.extend_from_slice(&comp_len.to_le_bytes());
            cd.extend_from_slice(&e.declared_uncompressed.to_le_bytes());
            cd.extend_from_slice(&name_len.to_le_bytes());
            cd.extend_from_slice(&0u16.to_le_bytes()); // extra len
            cd.extend_from_slice(&0u16.to_le_bytes()); // comment len
            cd.extend_from_slice(&0u16.to_le_bytes()); // disk number start
            cd.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
            cd.extend_from_slice(&0u32.to_le_bytes()); // external attrs
            cd.extend_from_slice(&lfh_offset.to_le_bytes());
            cd.extend_from_slice(name_bytes);
        }

        let cd_offset = out.len() as u32;
        let cd_size = cd.len() as u32;
        out.extend_from_slice(&cd);

        let count = entries.len() as u16;
        out.extend_from_slice(&ZIP_EOCD_SIG);
        out.extend_from_slice(&0u16.to_le_bytes()); // disk num
        out.extend_from_slice(&0u16.to_le_bytes()); // cd disk
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&cd_size.to_le_bytes());
        out.extend_from_slice(&cd_offset.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // comment len
        out
    }

    #[test]
    fn png_valid_2x2_passes() {
        let png = build_valid_png_2x2();
        let report = verify_png(&png, &SecurityPolicy::default()).expect("valid 2x2 PNG");
        assert_eq!(report.kind, MediaKind::Png);
        assert_eq!(report.input_bytes, png.len());
    }

    #[test]
    fn png_pixel_flood_65535x65535_rejected() {
        let mut png = Vec::new();
        png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(&mut png, b"IHDR", &build_ihdr_data(65_535, 65_535, 8, 6));
        push_png_chunk(&mut png, b"IDAT", &zlib_compress(&[0u8; 4]));
        push_png_chunk(&mut png, b"IEND", &[]);

        let err = verify_png(&png, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err,
            SanitizationError::ImageDimensionExceeded {
                width: 65_535,
                height: 65_535,
                pixels: 65_535_u64 * 65_535_u64,
            }
        );
    }

    #[test]
    fn png_ztxt_zlib_bomb_rejected() {
        let mut png = Vec::new();
        png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(&mut png, b"IHDR", &build_ihdr_data(2, 2, 8, 6));

        let mut ztxt = b"Comment\0\0".to_vec();
        ztxt.extend_from_slice(&zlib_compress(&vec![0u8; 200_000]));
        push_png_chunk(&mut png, b"zTXt", &ztxt);

        push_png_chunk(&mut png, b"IDAT", &zlib_compress(&vec![0u8; 18]));
        push_png_chunk(&mut png, b"IEND", &[]);

        let err = verify_png(&png, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn png_itxt_compressed_bomb_rejected() {
        let mut png = Vec::new();
        png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(&mut png, b"IHDR", &build_ihdr_data(2, 2, 8, 6));

        // keyword\0 + compression_flag(1) + compression_method(0) + lang\0 + trans\0 + zlib
        let mut itxt = b"XML:com.adobe.xmp\0\x01\x00en\0\0".to_vec();
        itxt.extend_from_slice(&zlib_compress(&vec![b'A'; 128_000]));
        push_png_chunk(&mut png, b"iTXt", &itxt);

        push_png_chunk(&mut png, b"IDAT", &zlib_compress(&vec![0u8; 18]));
        push_png_chunk(&mut png, b"IEND", &[]);

        let err = verify_png(&png, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn png_idat_zlib_bomb_rejected() {
        let mut png = Vec::new();
        png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(&mut png, b"IHDR", &build_ihdr_data(2, 2, 8, 6));
        push_png_chunk(&mut png, b"IDAT", &zlib_compress(&vec![0u8; 50_000]));
        push_png_chunk(&mut png, b"IEND", &[]);

        let err = verify_png(&png, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn png_truncated_8_bytes_and_bad_crc_rejected() {
        let err = verify_png(&PNG_SIGNATURE, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(err, SanitizationError::InvalidFormat(_)));

        let mut corrupted = build_valid_png_2x2();
        let last = corrupted.len() - 1;
        corrupted[last] ^= 0xFF; // adulterar CRC do IEND
        let err_crc = verify_png(&corrupted, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(err_crc, SanitizationError::InvalidFormat(_)));

        let mut trailing = build_valid_png_2x2();
        trailing.extend_from_slice(b"POLYGLOT");
        let err_trail = verify_png(&trailing, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(err_trail, SanitizationError::InvalidFormat(_)));
    }

    #[test]
    fn jpeg_valid_100x100_passes_and_invalid_cases_rejected() {
        let valid = build_minimal_jpeg(100, 100);
        let report = verify_jpeg(&valid, &SecurityPolicy::default()).expect("valid JPEG");
        assert_eq!(report.kind, MediaKind::Jpeg);

        let flood = build_minimal_jpeg(65_535, 65_535);
        let err_flood = verify_jpeg(&flood, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err_flood,
            SanitizationError::ImageDimensionExceeded {
                width: 65_535,
                height: 65_535,
                pixels: 65_535_u64 * 65_535_u64,
            }
        );

        let truncated = [0xFF, 0xD8, 0xFF];
        let err_trunc = verify_jpeg(&truncated, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(err_trunc, SanitizationError::InvalidFormat(_)));

        let no_sof = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x02, 0xFF, 0xD9];
        let err_no_sof = verify_jpeg(&no_sof, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(err_no_sof, SanitizationError::InvalidFormat(_)));
    }

    #[test]
    fn jpeg_with_sos_byte_stuffing_passes() {
        let mut jpeg = Vec::new();
        jpeg.extend_from_slice(&[0xFF, 0xD8]);
        // SOF0 16x16, 1 component
        jpeg.extend_from_slice(&[0xFF, 0xC0, 0x00, 11, 8, 0, 16, 0, 16, 1, 1, 0x11, 0]);
        // SOS header: len=8 (2 + 1 + 2 + 3)
        jpeg.extend_from_slice(&[0xFF, 0xDA, 0x00, 8, 1, 1, 0, 0, 63, 0]);
        // Entropy-coded data with stuffed FF 00 and restart FF D0
        jpeg.extend_from_slice(&[0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56]);
        // EOI
        jpeg.extend_from_slice(&[0xFF, 0xD9]);

        let report = verify_jpeg(&jpeg, &SecurityPolicy::default()).expect("valid JPEG with SOS");
        assert_eq!(report.kind, MediaKind::Jpeg);
    }

    #[test]
    fn zip_with_valid_png_passes() {
        let png = build_valid_png_2x2();
        let compressed = deflate_compress(&png);
        let zip = build_zip(&[ZipEntrySpec {
            name: "document.png",
            method: 8,
            compressed,
            declared_uncompressed: png.len() as u32,
            crc32: crc32fast::hash(&png),
        }]);

        let report = verify_image_zip(&zip, &SecurityPolicy::default()).expect("valid image ZIP");
        assert_eq!(report.kind, MediaKind::Zip);
    }

    #[test]
    fn zip_classic_deflate_bomb_rejected() {
        let bomb_raw = vec![0u8; 10 * 1024 * 1024]; // 10 MB de zeros
        let compressed = deflate_compress(&bomb_raw);
        let zip = build_zip(&[ZipEntrySpec {
            name: "bomb.png",
            method: 8,
            compressed,
            declared_uncompressed: bomb_raw.len() as u32,
            crc32: crc32fast::hash(&bomb_raw),
        }]);

        let err = verify_image_zip(&zip, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn zip_spoofed_uncompressed_header_bomb_rejected() {
        // Header mente dizendo uncompressed_size=16, mas stream DEFLATE real explode > 16 e > ratio
        let real_payload = vec![0u8; 512 * 1024];
        let compressed = deflate_compress(&real_payload);
        let zip = build_zip(&[ZipEntrySpec {
            name: "spoof.png",
            method: 8,
            compressed,
            declared_uncompressed: 16,
            crc32: crc32fast::hash(&real_payload),
        }]);

        let err = verify_image_zip(&zip, &SecurityPolicy::default()).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn zip_nested_archive_and_path_traversal_rejected() {
        let png = build_valid_png_2x2();
        let inner_zip = build_zip(&[ZipEntrySpec {
            name: "inner.png",
            method: 0,
            compressed: png.clone(),
            declared_uncompressed: png.len() as u32,
            crc32: crc32fast::hash(&png),
        }]);
        let outer_nested = build_zip(&[ZipEntrySpec {
            name: "nested.zip",
            method: 0,
            compressed: inner_zip.clone(),
            declared_uncompressed: inner_zip.len() as u32,
            crc32: crc32fast::hash(&inner_zip),
        }]);

        let err_nested = verify_image_zip(&outer_nested, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err_nested,
            SanitizationError::NestedArchiveDetected("nested.zip".to_string())
        );

        let disguised_nested = build_zip(&[ZipEntrySpec {
            name: "disguised.png",
            method: 8,
            compressed: deflate_compress(&inner_zip),
            declared_uncompressed: inner_zip.len() as u32,
            crc32: crc32fast::hash(&inner_zip),
        }]);
        let err_disguised =
            verify_image_zip(&disguised_nested, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err_disguised,
            SanitizationError::NestedArchiveDetected("disguised.png".to_string())
        );

        let traversal_zip = build_zip(&[ZipEntrySpec {
            name: "../../etc/passwd",
            method: 0,
            compressed: png.clone(),
            declared_uncompressed: png.len() as u32,
            crc32: crc32fast::hash(&png),
        }]);

        let err_trav = verify_image_zip(&traversal_zip, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err_trav,
            SanitizationError::PathTraversalDetected("../../etc/passwd".to_string())
        );
    }

    #[test]
    fn zip_containing_png_pixel_flood_65535x65535_rejected() {
        let mut flood_png = Vec::new();
        flood_png.extend_from_slice(&PNG_SIGNATURE);
        push_png_chunk(
            &mut flood_png,
            b"IHDR",
            &build_ihdr_data(65_535, 65_535, 8, 6),
        );
        push_png_chunk(&mut flood_png, b"IDAT", &zlib_compress(&[0u8; 4]));
        push_png_chunk(&mut flood_png, b"IEND", &[]);

        let zip = build_zip(&[ZipEntrySpec {
            name: "flood.png",
            method: 8,
            compressed: deflate_compress(&flood_png),
            declared_uncompressed: flood_png.len() as u32,
            crc32: crc32fast::hash(&flood_png),
        }]);

        let err = verify_image_zip(&zip, &SecurityPolicy::default()).unwrap_err();
        assert_eq!(
            err,
            SanitizationError::ImageDimensionExceeded {
                width: 65_535,
                height: 65_535,
                pixels: 65_535_u64 * 65_535_u64,
            }
        );
    }

    #[test]
    fn zip_exceeding_max_entries_rejected() {
        let png = build_valid_png_2x2();
        let policy = SecurityPolicy {
            max_zip_entries: 1,
            ..SecurityPolicy::default()
        };
        let zip = build_zip(&[
            ZipEntrySpec {
                name: "one.png",
                method: 0,
                compressed: png.clone(),
                declared_uncompressed: png.len() as u32,
                crc32: crc32fast::hash(&png),
            },
            ZipEntrySpec {
                name: "two.png",
                method: 0,
                compressed: png.clone(),
                declared_uncompressed: png.len() as u32,
                crc32: crc32fast::hash(&png),
            },
        ]);

        let err = verify_image_zip(&zip, &policy).unwrap_err();
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }
}
