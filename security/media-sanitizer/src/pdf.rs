use std::io::Read;

use flate2::read::ZlibDecoder;

use crate::{MediaKind, SanitizationError, SanitizationReport, SecurityPolicy};

const PDF_STREAM_MIN_RATIO_THRESHOLD: usize = 4 * 1024;

const DANGEROUS_PDF_NAMES: &[&[u8]] = &[
    b"JavaScript",
    b"JS",
    b"OpenAction",
    b"AA",
    b"Launch",
    b"GoToR",
    b"GoToE",
    b"SubmitForm",
    b"ImportData",
    b"ResetForm",
    b"RichMedia",
    b"EmbeddedFile",
    b"Filespec",
    b"XFA",
    b"Sound",
    b"Movie",
    b"3D",
];

struct StreamSpan<'a> {
    dict: &'a [u8],
    data: &'a [u8],
    structural_before: &'a [u8],
}

/// Verifica integridade estrutural, previne polyglots, PDF injection (inclusive nomes
/// ofuscados com `#xx`) e bombas de descompressao `FlateDecode` em arquivos PDF.
pub fn verify_pdf(
    bytes: &[u8],
    policy: &SecurityPolicy,
) -> Result<SanitizationReport, SanitizationError> {
    if bytes.is_empty() {
        return Err(SanitizationError::EmptyInput);
    }
    if bytes.len() > policy.max_input_bytes {
        return Err(SanitizationError::InputTooLarge {
            actual: bytes.len(),
            max: policy.max_input_bytes,
        });
    }

    validate_header(bytes)?;
    let eof_pos = validate_eof_and_suffix(bytes)?;

    let body = &bytes[..eof_pos];
    let (streams, trailing_structural) = extract_streams(body)?;

    let mut object_count = 0usize;
    let mut has_xref = false;
    let mut has_startxref = false;

    for span in &streams {
        inspect_structural_slice(
            span.structural_before,
            &mut object_count,
            &mut has_xref,
            &mut has_startxref,
        )?;
        if object_count > policy.max_pdf_objects {
            return Err(SanitizationError::InvalidFormat(format!(
                "PDF object count exceeds maximum ({object_count} > {})",
                policy.max_pdf_objects
            )));
        }
    }

    inspect_structural_slice(
        trailing_structural,
        &mut object_count,
        &mut has_xref,
        &mut has_startxref,
    )?;
    if object_count > policy.max_pdf_objects {
        return Err(SanitizationError::InvalidFormat(format!(
            "PDF object count exceeds maximum ({object_count} > {})",
            policy.max_pdf_objects
        )));
    }

    if !has_xref {
        return Err(SanitizationError::InvalidFormat(
            "missing xref table or /XRef stream".to_string(),
        ));
    }
    if !has_startxref {
        return Err(SanitizationError::InvalidFormat(
            "missing startxref marker".to_string(),
        ));
    }

    let mut total_decompressed = 0usize;
    for (idx, span) in streams.iter().enumerate() {
        let stream_index = idx + 1;
        inspect_stream(span, stream_index, policy, &mut total_decompressed)?;
    }

    Ok(SanitizationReport {
        kind: MediaKind::Pdf,
        input_bytes: bytes.len(),
        summary: format!(
            "valid PDF ({object_count} objects, {} streams, {total_decompressed} decompressed bytes)",
            streams.len()
        ),
    })
}

fn validate_header(bytes: &[u8]) -> Result<(), SanitizationError> {
    let has_valid_version = bytes.len() >= 8
        && (bytes.starts_with(b"%PDF-1.") || bytes.starts_with(b"%PDF-2."))
        && bytes[7].is_ascii_digit();
    if has_valid_version {
        return Ok(());
    }

    let prefix_len = bytes.len().min(4096);
    check_polyglot_signatures(&bytes[..prefix_len], "file header prefix")?;

    if bytes.windows(5).any(|w| w == b"%PDF-") {
        return Err(SanitizationError::PolyglotDetected(
            "displaced %PDF- signature not at offset 0".to_string(),
        ));
    }

    Err(SanitizationError::InvalidFormat(
        "missing valid %PDF-1.x or %PDF-2.0 header at offset 0".to_string(),
    ))
}

fn validate_eof_and_suffix(bytes: &[u8]) -> Result<usize, SanitizationError> {
    let eof_pos = bytes
        .windows(5)
        .rposition(|w| w == b"%%EOF")
        .ok_or_else(|| SanitizationError::InvalidFormat("missing %%EOF marker".to_string()))?;

    let after_eof = &bytes[eof_pos + 5..];
    if after_eof.iter().any(|&b| !is_pdf_whitespace(b)) {
        check_polyglot_signatures(after_eof, "suffix after %%EOF")?;
        return Err(SanitizationError::PolyglotDetected(format!(
            "unexpected payload ({} bytes) after %%EOF",
            after_eof.len()
        )));
    }

    Ok(eof_pos)
}

fn inspect_structural_slice(
    slice: &[u8],
    object_count: &mut usize,
    has_xref: &mut bool,
    has_startxref: &mut bool,
) -> Result<(), SanitizationError> {
    check_polyglot_signatures(slice, "pdf body")?;
    scan_pdf_names(slice, "pdf body")?;

    *object_count = object_count.saturating_add(count_indirect_objects(slice));
    if !*has_xref && (has_keyword(slice, b"xref") || dict_has_pdf_name(slice, b"XRef")) {
        *has_xref = true;
    }
    if !*has_startxref && has_keyword(slice, b"startxref") {
        *has_startxref = true;
    }

    Ok(())
}

fn inspect_stream(
    span: &StreamSpan<'_>,
    stream_index: usize,
    policy: &SecurityPolicy,
    total_decompressed: &mut usize,
) -> Result<(), SanitizationError> {
    let has_flate_filter = dict_has_pdf_name(span.dict, b"FlateDecode");
    let has_incompatible_wrapper_filter = has_flate_filter && has_wrapper_filter(span.dict);
    let is_image = dict_has_pdf_name(span.dict, b"Image");
    let is_binary_font = dict_has_pdf_name(span.dict, b"FontFile")
        || dict_has_pdf_name(span.dict, b"FontFile2")
        || dict_has_pdf_name(span.dict, b"FontFile3");
    let skip_structural_scan = is_image || is_binary_font;
    let looks_like_zlib = !has_incompatible_wrapper_filter
        && span.data.len() >= 2
        && span.data[0] == 0x78
        && (((u16::from(span.data[0]) << 8) | u16::from(span.data[1])) % 31 == 0);

    if (has_flate_filter && !has_incompatible_wrapper_filter) || looks_like_zlib {
        if span.data.is_empty() {
            return Err(SanitizationError::InvalidFormat(format!(
                "empty FlateDecode stream #{stream_index}"
            )));
        }

        let remaining_global = policy
            .max_decompressed_bytes
            .saturating_sub(*total_decompressed);
        let max_by_ratio = (span.data.len() as u64).saturating_mul(policy.max_compression_ratio);
        let ratio_limit = if is_image {
            usize::MAX
        } else {
            usize::try_from(max_by_ratio)
                .unwrap_or(usize::MAX)
                .max(PDF_STREAM_MIN_RATIO_THRESHOLD)
        };
        let stream_limit = remaining_global.min(ratio_limit);

        let mut decoder = ZlibDecoder::new(span.data);
        let mut limited = (&mut decoder).take((stream_limit as u64).saturating_add(1));
        let mut decompressed = Vec::new();

        match limited.read_to_end(&mut decompressed) {
            Ok(_) => {
                if decompressed.len() > stream_limit {
                    return Err(SanitizationError::DecompressionBombDetected(format!(
                        "stream #{stream_index} exceeded decompression limit ({} > {stream_limit} bytes, compressed={} bytes)",
                        decompressed.len(),
                        span.data.len()
                    )));
                }
                *total_decompressed = total_decompressed.saturating_add(decompressed.len());
                let loc = format!("stream #{stream_index} (decompressed FlateDecode)");
                if !skip_structural_scan {
                    scan_pdf_names(&decompressed, &loc)?;
                    check_polyglot_signatures(&decompressed, &loc)?;
                }
            }
            Err(err) => {
                if has_flate_filter {
                    return Err(SanitizationError::InvalidFormat(format!(
                        "invalid FlateDecode stream #{stream_index}: {err}"
                    )));
                }
                let loc = format!("stream #{stream_index}");
                if !skip_structural_scan {
                    scan_pdf_names(span.data, &loc)?;
                    check_polyglot_signatures(span.data, &loc)?;
                }
            }
        }
    } else {
        let loc = format!("stream #{stream_index}");
        if !skip_structural_scan {
            scan_pdf_names(span.data, &loc)?;
            check_polyglot_signatures(span.data, &loc)?;
        }
    }

    Ok(())
}

fn has_wrapper_filter(dict: &[u8]) -> bool {
    const NON_RAW_ZLIB_FILTERS: &[&[u8]] = &[
        b"ASCII85Decode",
        b"ASCIIHexDecode",
        b"LZWDecode",
        b"RunLengthDecode",
        b"CCITTFaxDecode",
        b"JBIG2Decode",
        b"DCTDecode",
        b"JPXDecode",
        b"Crypt",
    ];
    NON_RAW_ZLIB_FILTERS
        .iter()
        .any(|filter| dict_has_pdf_name(dict, filter))
}

fn extract_streams<'a>(
    body: &'a [u8],
) -> Result<(Vec<StreamSpan<'a>>, &'a [u8]), SanitizationError> {
    let mut spans = Vec::new();
    let mut structural_start = 0usize;
    let mut i = 0usize;

    while i + 6 <= body.len() {
        if &body[i..i + 6] == b"stream"
            && (i == 0 || is_pdf_boundary(body[i - 1]))
            && preceding_token_is_dict_end(&body[structural_start..i])
        {
            let data_start = if i + 7 < body.len() && body[i + 6] == b'\r' && body[i + 7] == b'\n' {
                Some(i + 8)
            } else if i + 6 < body.len() && (body[i + 6] == b'\n' || body[i + 6] == b'\r') {
                Some(i + 7)
            } else {
                None
            };

            if let Some(data_start) = data_start {
                let dict = find_preceding_dict(&body[structural_start..i]).unwrap_or(&[]);
                let (data_end, after_endstream) = find_stream_end(body, data_start, dict)
                    .ok_or_else(|| {
                        SanitizationError::InvalidFormat(
                            "unterminated PDF stream (missing endstream)".to_string(),
                        )
                    })?;

                spans.push(StreamSpan {
                    dict,
                    data: &body[data_start..data_end],
                    structural_before: &body[structural_start..data_start],
                });

                structural_start = after_endstream;
                i = after_endstream;
                continue;
            }
        }
        i += 1;
    }

    Ok((spans, &body[structural_start..]))
}

fn preceding_token_is_dict_end(prefix: &[u8]) -> bool {
    let mut end = prefix.len();
    while end > 0 && is_pdf_whitespace(prefix[end - 1]) {
        end -= 1;
    }
    end >= 2 && &prefix[end - 2..end] == b">>"
}

fn find_preceding_dict(prefix: &[u8]) -> Option<&[u8]> {
    let mut end = prefix.len();
    while end > 0 && is_pdf_whitespace(prefix[end - 1]) {
        end -= 1;
    }
    if end < 2 || &prefix[end - 2..end] != b">>" {
        return None;
    }

    let mut depth = 0i32;
    let mut i = end;
    while i >= 2 {
        if &prefix[i - 2..i] == b">>" {
            depth += 1;
            i -= 2;
        } else if &prefix[i - 2..i] == b"<<" {
            depth -= 1;
            if depth == 0 {
                return Some(&prefix[i - 2..end]);
            }
            i -= 2;
        } else {
            i -= 1;
        }
    }
    None
}

fn find_stream_end(body: &[u8], data_start: usize, dict: &[u8]) -> Option<(usize, usize)> {
    if let Some(declared_len) = parse_inline_length(dict) {
        if let Some(candidate_end) = data_start.checked_add(declared_len) {
            if candidate_end <= body.len() {
                let mut probe = candidate_end;
                while probe < body.len() && matches!(body[probe], b'\r' | b'\n' | b' ') {
                    probe += 1;
                }
                if probe + 9 <= body.len()
                    && &body[probe..probe + 9] == b"endstream"
                    && (probe + 9 == body.len() || is_pdf_boundary(body[probe + 9]))
                {
                    return Some((candidate_end, probe + 9));
                }
            }
        }
    }

    let mut pos = data_start;
    while pos + 9 <= body.len() {
        if &body[pos..pos + 9] == b"endstream"
            && (pos + 9 == body.len() || is_pdf_boundary(body[pos + 9]))
        {
            let mut data_end = pos;
            if data_end >= data_start + 2 && &body[data_end - 2..data_end] == b"\r\n" {
                data_end -= 2;
            } else if data_end > data_start
                && (body[data_end - 1] == b'\n' || body[data_end - 1] == b'\r')
            {
                data_end -= 1;
            }
            return Some((data_end, pos + 9));
        }
        pos += 1;
    }

    None
}

fn parse_inline_length(dict: &[u8]) -> Option<usize> {
    let mut i = 0usize;
    while i < dict.len() {
        if dict[i] == b'/' {
            let start = i + 1;
            let mut end = start;
            while end < dict.len() && !is_pdf_boundary(dict[end]) {
                end += 1;
            }
            if end > start && decode_pdf_name(&dict[start..end]).as_slice() == b"Length" {
                let mut j = end;
                while j < dict.len() && is_pdf_whitespace(dict[j]) {
                    j += 1;
                }
                let num_start = j;
                while j < dict.len() && dict[j].is_ascii_digit() {
                    j += 1;
                }
                if j > num_start {
                    let mut k = j;
                    while k < dict.len() && is_pdf_whitespace(dict[k]) {
                        k += 1;
                    }
                    let gen_start = k;
                    while k < dict.len() && dict[k].is_ascii_digit() {
                        k += 1;
                    }
                    if k > gen_start {
                        while k < dict.len() && is_pdf_whitespace(dict[k]) {
                            k += 1;
                        }
                        if k < dict.len() && dict[k] == b'R' {
                            i = end;
                            continue;
                        }
                    }
                    if let Ok(s) = std::str::from_utf8(&dict[num_start..j]) {
                        if let Ok(val) = s.parse::<usize>() {
                            return Some(val);
                        }
                    }
                }
            }
            i = end;
        } else {
            i += 1;
        }
    }
    None
}

fn scan_pdf_names(data: &[u8], location: &str) -> Result<(), SanitizationError> {
    let mut i = 0usize;
    while i < data.len() {
        if data[i] == b'(' {
            if let Some(after_string) = find_balanced_pdf_string_end(data, i) {
                i = after_string;
                continue;
            }
        }
        if data[i] == b'/' {
            let start = i + 1;
            let mut end = start;
            while end < data.len() && !is_pdf_boundary(data[end]) {
                end += 1;
            }
            if end > start {
                let raw_name = &data[start..end];
                let decoded = decode_pdf_name(raw_name);
                if let Some(&danger) = DANGEROUS_PDF_NAMES
                    .iter()
                    .find(|&&name| name == decoded.as_slice())
                {
                    let indicator = format!("/{}", String::from_utf8_lossy(danger));
                    let loc = if raw_name != decoded.as_slice() {
                        let raw_str = String::from_utf8_lossy(raw_name);
                        format!("{location} (obfuscated as /{raw_str} at offset {i})")
                    } else {
                        format!("{location} (at offset {i})")
                    };
                    return Err(SanitizationError::PdfInjectionDetected {
                        indicator,
                        location: loc,
                    });
                }
            }
            i = end;
        } else {
            i += 1;
        }
    }
    Ok(())
}

fn find_balanced_pdf_string_end(data: &[u8], start: usize) -> Option<usize> {
    let mut depth = 0usize;
    let mut i = start;
    while i < data.len() {
        match data[i] {
            b'\\' => {
                // Skip escaped character inside literal string
                i += 2;
                continue;
            }
            b'(' => depth += 1,
            b')' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    return Some(i + 1);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

fn dict_has_pdf_name(dict: &[u8], target: &[u8]) -> bool {
    let mut i = 0usize;
    while i < dict.len() {
        if dict[i] == b'/' {
            let start = i + 1;
            let mut end = start;
            while end < dict.len() && !is_pdf_boundary(dict[end]) {
                end += 1;
            }
            if end > start && decode_pdf_name(&dict[start..end]).as_slice() == target {
                return true;
            }
            i = end;
        } else {
            i += 1;
        }
    }
    false
}

fn decode_pdf_name(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0usize;
    while i < raw.len() {
        if raw[i] == b'#' && i + 2 < raw.len() {
            if let (Some(hi), Some(lo)) = (hex_value(raw[i + 1]), hex_value(raw[i + 2])) {
                let decoded = (hi << 4) | lo;
                if decoded != 0 {
                    out.push(decoded);
                }
                i += 3;
                continue;
            }
        }
        if raw[i] != 0 {
            out.push(raw[i]);
        }
        i += 1;
    }
    out
}

fn hex_value(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn check_polyglot_signatures(data: &[u8], location: &str) -> Result<(), SanitizationError> {
    const BINARY_SIGS: &[(&[u8], &str)] = &[
        (b"PK\x03\x04", "ZIP local file header (PK\\x03\\x04)"),
        (b"MZ\x90\x00", "PE/MZ executable header"),
        (b"\x7fELF", "ELF executable header"),
    ];
    for &(sig, label) in BINARY_SIGS {
        if data.windows(sig.len()).any(|w| w == sig) {
            return Err(SanitizationError::PolyglotDetected(format!(
                "{label} found in {location}"
            )));
        }
    }

    const ASCII_ICASE_SIGS: &[(&[u8], &str)] = &[
        (b"<script", "HTML <script> tag"),
        (b"<html", "HTML <html> tag"),
        (b"<!doctype", "HTML <!DOCTYPE> declaration"),
        (b"<?php", "PHP <?php tag"),
    ];
    for &(sig, label) in ASCII_ICASE_SIGS {
        if data.windows(sig.len()).any(|w| w.eq_ignore_ascii_case(sig)) {
            return Err(SanitizationError::PolyglotDetected(format!(
                "{label} found in {location}"
            )));
        }
    }

    Ok(())
}

fn count_indirect_objects(data: &[u8]) -> usize {
    let mut count = 0usize;
    let mut i = 0usize;

    while i < data.len() {
        if data[i].is_ascii_digit() && (i == 0 || is_pdf_boundary(data[i - 1])) {
            let mut j = i;
            while j < data.len() && data[j].is_ascii_digit() {
                j += 1;
            }
            let ws1 = j;
            while j < data.len() && is_pdf_whitespace(data[j]) {
                j += 1;
            }
            if j > ws1 {
                let gen_start = j;
                while j < data.len() && data[j].is_ascii_digit() {
                    j += 1;
                }
                if j > gen_start {
                    let ws2 = j;
                    while j < data.len() && is_pdf_whitespace(data[j]) {
                        j += 1;
                    }
                    if j > ws2
                        && j + 3 <= data.len()
                        && &data[j..j + 3] == b"obj"
                        && (j + 3 == data.len() || is_pdf_boundary(data[j + 3]))
                    {
                        count += 1;
                        i = j + 3;
                        continue;
                    }
                }
            }
            i = ws1;
        } else {
            i += 1;
        }
    }

    count
}

fn has_keyword(data: &[u8], kw: &[u8]) -> bool {
    if kw.is_empty() || data.len() < kw.len() {
        return false;
    }
    data.windows(kw.len()).enumerate().any(|(idx, w)| {
        w == kw
            && (idx == 0 || is_pdf_boundary(data[idx - 1]))
            && (idx + kw.len() == data.len() || is_pdf_boundary(data[idx + kw.len()]))
    })
}

fn is_pdf_whitespace(b: u8) -> bool {
    matches!(b, 0x00 | 0x09 | 0x0A | 0x0C | 0x0D | 0x20)
}

fn is_pdf_delimiter(b: u8) -> bool {
    matches!(
        b,
        b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%'
    )
}

fn is_pdf_boundary(b: u8) -> bool {
    is_pdf_whitespace(b) || is_pdf_delimiter(b)
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use flate2::write::ZlibEncoder;
    use flate2::Compression;

    use super::*;

    fn compress_zlib(data: &[u8]) -> Vec<u8> {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::best());
        encoder.write_all(data).expect("zlib write");
        encoder.finish().expect("zlib finish")
    }

    fn build_minimal_pdf_with_catalog_extra(catalog_extra: &str) -> Vec<u8> {
        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.4\n");
        let off1 = pdf.len();
        pdf.extend_from_slice(
            format!("1 0 obj\n<< /Type /Catalog /Pages 2 0 R {catalog_extra}>>\nendobj\n")
                .as_bytes(),
        );
        let off2 = pdf.len();
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        let off3 = pdf.len();
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n",
        );
        let xref_off = pdf.len();
        pdf.extend_from_slice(
            format!(
                "xref\n0 4\n0000000000 65535 f \n{off1:010} 00000 n \n{off2:010} 00000 n \n{off3:010} 00000 n \ntrailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n{xref_off}\n%%EOF\n"
            )
            .as_bytes(),
        );
        pdf
    }

    fn build_pdf_with_stream(dict_extra: &str, stream_bytes: &[u8]) -> Vec<u8> {
        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.4\n");
        let off1 = pdf.len();
        pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        let off2 = pdf.len();
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        let off3 = pdf.len();
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n",
        );
        let off4 = pdf.len();
        pdf.extend_from_slice(
            format!(
                "4 0 obj\n<< /Length {} {dict_extra}>>\nstream\n",
                stream_bytes.len()
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(stream_bytes);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        let xref_off = pdf.len();
        pdf.extend_from_slice(
            format!(
                "xref\n0 5\n0000000000 65535 f \n{off1:010} 00000 n \n{off2:010} 00000 n \n{off3:010} 00000 n \n{off4:010} 00000 n \ntrailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref_off}\n%%EOF\n"
            )
            .as_bytes(),
        );
        pdf
    }

    #[test]
    fn accepts_minimal_valid_pdf() {
        let pdf = build_minimal_pdf_with_catalog_extra("");
        let report = verify_pdf(&pdf, &SecurityPolicy::default()).expect("valid PDF should pass");
        assert_eq!(report.kind, MediaKind::Pdf);
        assert_eq!(report.input_bytes, pdf.len());
    }

    #[test]
    fn accepts_valid_pdf_with_safe_flate_stream() {
        let content = b"BT /F1 12 Tf 72 720 Td (V-Stable Compliance Document) Tj ET";
        let compressed = compress_zlib(content);
        let pdf = build_pdf_with_stream("/Filter /FlateDecode ", &compressed);
        let report =
            verify_pdf(&pdf, &SecurityPolicy::default()).expect("safe FlateDecode PDF passes");
        assert_eq!(report.kind, MediaKind::Pdf);
    }

    #[test]
    fn rejects_truncated_pdf() {
        let err = verify_pdf(b"%PDF-1.7", &SecurityPolicy::default())
            .expect_err("truncated 8-byte PDF must fail");
        assert!(matches!(err, SanitizationError::InvalidFormat(_)));
    }

    #[test]
    fn rejects_pdf_exceeding_max_objects() {
        let pdf = build_minimal_pdf_with_catalog_extra("");
        let policy = SecurityPolicy {
            max_pdf_objects: 2,
            ..SecurityPolicy::default()
        };
        let err = verify_pdf(&pdf, &policy).expect_err("object limit exceeded must fail");
        assert!(matches!(err, SanitizationError::InvalidFormat(_)));
    }

    #[test]
    fn rejects_dangerous_pdf_injection_keys() {
        let dangerous_cases = [
            ("/JavaScript", "/S /JavaScript "),
            ("/OpenAction", "/OpenAction << /S /JS >> "),
            ("/Launch", "/A << /S /Launch >> "),
            ("/RichMedia", "/Annots [<< /Subtype /RichMedia >>] "),
            ("/EmbeddedFile", "/Names << /EmbeddedFile 5 0 R >> "),
            ("/SubmitForm", "/A << /S /SubmitForm >> "),
            ("/XFA", "/XFA 6 0 R "),
        ];

        for (expected_indicator, extra) in dangerous_cases {
            let pdf = build_minimal_pdf_with_catalog_extra(extra);
            let err = verify_pdf(&pdf, &SecurityPolicy::default())
                .expect_err("dangerous PDF key must be rejected");
            match err {
                SanitizationError::PdfInjectionDetected { indicator, .. } => {
                    assert_eq!(indicator, expected_indicator);
                }
                other => {
                    panic!("expected PdfInjectionDetected for {expected_indicator}, got {other:?}")
                }
            }
        }
    }

    #[test]
    fn accepts_legitimate_pdf_with_links_acroform_objstm_and_image_stream() {
        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.7\n");
        let off1 = pdf.len();
        pdf.extend_from_slice(
            b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm << /SigFlags 3 >> >>\nendobj\n",
        );
        let off2 = pdf.len();
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        let off3 = pdf.len();
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Annots [<< /Subtype /Link /A << /S /URI /URI (https://vstable.test/doc) >> >>] >>\nendobj\n",
        );
        let off4 = pdf.len();
        let small_text_stream = compress_zlib(&vec![b' '; 3000]);
        pdf.extend_from_slice(
            format!(
                "4 0 obj\n<< /Length {} /Filter /FlateDecode >>\nstream\n",
                small_text_stream.len()
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(&small_text_stream);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        let off5 = pdf.len();
        // Image stream containing arbitrary bytes including /AA /JS sequences (must not trigger false positive)
        let raw_img_bytes = b"\xFF\xD8\xFF\xE0binary/AA /JS /3D pixels\xFF\xD9";
        pdf.extend_from_slice(
            format!(
                "5 0 obj\n<< /Type /XObject /Subtype /Image /Width 10 /Height 10 /Filter /DCTDecode /Length {} >>\nstream\n",
                raw_img_bytes.len()
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(raw_img_bytes);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        let off6 = pdf.len();
        let objstm_payload = compress_zlib(b"7 0 << /Title (Contrato /JS texto) >>");
        pdf.extend_from_slice(
            format!(
                "6 0 obj\n<< /Type /ObjStm /N 1 /First 5 /Filter /FlateDecode /Length {} >>\nstream\n",
                objstm_payload.len()
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(&objstm_payload);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        let xref_off = pdf.len();
        pdf.extend_from_slice(
            format!(
                "xref\n0 7\n0000000000 65535 f \n{off1:010} 00000 n \n{off2:010} 00000 n \n{off3:010} 00000 n \n{off4:010} 00000 n \n{off5:010} 00000 n \n{off6:010} 00000 n \ntrailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n{xref_off}\n%%EOF\n"
            )
            .as_bytes(),
        );

        let report = verify_pdf(&pdf, &SecurityPolicy::default())
            .expect("legitimate modern PDF with links, AcroForm, ObjStm and image must pass");
        assert_eq!(report.kind, MediaKind::Pdf);
    }

    #[test]
    fn rejects_hex_obfuscated_pdf_names() {
        let obfuscated_cases = [
            ("/JavaScript", "/S /J#61vaScript "),
            ("/OpenAction", "/O#70enAction 9 0 R "),
            ("/JS", "/#4a#53 (app.alert(1)) "),
        ];

        for (expected_indicator, extra) in obfuscated_cases {
            let pdf = build_minimal_pdf_with_catalog_extra(extra);
            let err = verify_pdf(&pdf, &SecurityPolicy::default())
                .expect_err("obfuscated PDF key must be rejected");
            match err {
                SanitizationError::PdfInjectionDetected {
                    indicator,
                    location,
                } => {
                    assert_eq!(indicator, expected_indicator);
                    assert!(location.contains("obfuscated"));
                }
                other => panic!("expected PdfInjectionDetected, got {other:?}"),
            }
        }
    }

    #[test]
    fn rejects_javascript_hidden_inside_flatedecode_stream() {
        let payload = b"<< /S /JavaScript /JS (app.alert(1)) >>";
        let compressed = compress_zlib(payload);
        let pdf = build_pdf_with_stream("/Filter /FlateDecode ", &compressed);

        let err = verify_pdf(&pdf, &SecurityPolicy::default())
            .expect_err("hidden /JavaScript in FlateDecode must be rejected");
        match err {
            SanitizationError::PdfInjectionDetected {
                indicator,
                location,
            } => {
                assert_eq!(indicator, "/JavaScript");
                assert!(location.contains("decompressed"));
            }
            other => panic!("expected PdfInjectionDetected, got {other:?}"),
        }
    }

    #[test]
    fn rejects_flatedecode_decompression_bomb() {
        let zeros = vec![0u8; 64 * 1024];
        let compressed = compress_zlib(&zeros);
        let pdf = build_pdf_with_stream("/Filter /FlateDecode ", &compressed);

        let err = verify_pdf(&pdf, &SecurityPolicy::default())
            .expect_err("high-ratio FlateDecode bomb must be rejected");
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn rejects_flatedecode_exceeding_decompressed_byte_limit() {
        let data: Vec<u8> = (0..4096).map(|i| (i % 13) as u8).collect();
        let compressed = compress_zlib(&data);
        let pdf = build_pdf_with_stream("/Filter /FlateDecode ", &compressed);
        let policy = SecurityPolicy {
            max_decompressed_bytes: 1024,
            max_compression_ratio: 100_000,
            ..SecurityPolicy::default()
        };

        let err = verify_pdf(&pdf, &policy)
            .expect_err("stream exceeding max_decompressed_bytes must be rejected");
        assert!(matches!(
            err,
            SanitizationError::DecompressionBombDetected(_)
        ));
    }

    #[test]
    fn rejects_polyglot_payload_after_eof_and_before_header() {
        let base_pdf = build_minimal_pdf_with_catalog_extra("");

        let mut html_polyglot = base_pdf.clone();
        html_polyglot.extend_from_slice(b"<script>alert('xss')</script>");
        let err_html = verify_pdf(&html_polyglot, &SecurityPolicy::default())
            .expect_err("HTML/script suffix after %%EOF must be rejected");
        assert!(matches!(err_html, SanitizationError::PolyglotDetected(_)));

        let mut pe_polyglot = base_pdf.clone();
        pe_polyglot.extend_from_slice(b"MZ\x90\x00\x03\x00\x00\x00");
        let err_pe = verify_pdf(&pe_polyglot, &SecurityPolicy::default())
            .expect_err("PE executable suffix after %%EOF must be rejected");
        assert!(matches!(err_pe, SanitizationError::PolyglotDetected(_)));

        let mut zip_prefix_polyglot = b"PK\x03\x04\x14\x00\x00\x00".to_vec();
        zip_prefix_polyglot.extend_from_slice(&base_pdf);
        let err_zip = verify_pdf(&zip_prefix_polyglot, &SecurityPolicy::default())
            .expect_err("ZIP prefix before %PDF- must be rejected");
        assert!(matches!(err_zip, SanitizationError::PolyglotDetected(_)));
    }
}
