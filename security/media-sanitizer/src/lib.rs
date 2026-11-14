pub mod image_bomb;
pub mod pdf;

use std::fmt;

/// Politica de limites de seguranca aplicada na validacao de uploads.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecurityPolicy {
    pub max_input_bytes: usize,
    pub max_decompressed_bytes: usize,
    pub max_compression_ratio: u64,
    pub max_image_width: u32,
    pub max_image_height: u32,
    pub max_image_pixels: u64,
    pub max_zip_entries: usize,
    pub max_pdf_objects: usize,
}

impl Default for SecurityPolicy {
    fn default() -> Self {
        Self {
            // Alinhado ao limite de 3 MB de compliance em application.yaml
            max_input_bytes: 3 * 1024 * 1024,
            // Teto maximo de expansao para streams/arquivos em memoria (128 MiB, cobre PNG RGBA ate 25 MP)
            max_decompressed_bytes: 128 * 1024 * 1024,
            // Razao maxima entre bytes descomprimidos e comprimidos (100:1)
            max_compression_ratio: 100,
            max_image_width: 10_000,
            max_image_height: 10_000,
            // ~25 MP (~100 MB RGBA descompactado)
            max_image_pixels: 25_000_000,
            max_zip_entries: 64,
            max_pdf_objects: 20_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MediaKind {
    Pdf,
    Png,
    Jpeg,
    Zip,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SanitizationReport {
    pub kind: MediaKind,
    pub input_bytes: usize,
    pub summary: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SanitizationError {
    EmptyInput,
    InputTooLarge {
        actual: usize,
        max: usize,
    },
    InvalidFormat(String),
    PolyglotDetected(String),
    PdfInjectionDetected {
        indicator: String,
        location: String,
    },
    DecompressionBombDetected(String),
    ImageDimensionExceeded {
        width: u32,
        height: u32,
        pixels: u64,
    },
    PathTraversalDetected(String),
    NestedArchiveDetected(String),
}

impl fmt::Display for SanitizationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyInput => write!(f, "input file is empty"),
            Self::InputTooLarge { actual, max } => {
                write!(f, "input size {actual} bytes exceeds maximum {max} bytes")
            }
            Self::InvalidFormat(msg) => write!(f, "invalid file structure: {msg}"),
            Self::PolyglotDetected(msg) => write!(f, "polyglot/disguised payload detected: {msg}"),
            Self::PdfInjectionDetected {
                indicator,
                location,
            } => {
                write!(
                    f,
                    "PDF injection indicator '{indicator}' found in {location}"
                )
            }
            Self::DecompressionBombDetected(msg) => {
                write!(f, "decompression/zip bomb detected: {msg}")
            }
            Self::ImageDimensionExceeded {
                width,
                height,
                pixels,
            } => {
                write!(
                    f,
                    "image dimensions {width}x{height} ({pixels} px) exceed policy"
                )
            }
            Self::PathTraversalDetected(path) => {
                write!(f, "path traversal entry in archive: {path}")
            }
            Self::NestedArchiveDetected(name) => {
                write!(f, "nested archive is forbidden: {name}")
            }
        }
    }
}

impl std::error::Error for SanitizationError {}

/// Ponto de entrada unificado que detecta o tipo real pelos magic bytes
/// e executa o verificador correspondente.
pub fn validate_upload(
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

    if bytes.starts_with(b"%PDF-") {
        pdf::verify_pdf(bytes, policy)
    } else if bytes.starts_with(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) {
        image_bomb::verify_png(bytes, policy)
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        image_bomb::verify_jpeg(bytes, policy)
    } else if bytes.starts_with(b"PK\x03\x04") || bytes.starts_with(b"PK\x05\x06") {
        image_bomb::verify_image_zip(bytes, policy)
    } else {
        Err(SanitizationError::InvalidFormat(
            "unsupported or unrecognized file signature".to_string(),
        ))
    }
}
