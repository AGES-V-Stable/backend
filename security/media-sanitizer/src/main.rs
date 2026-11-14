use std::env;
use std::fmt::Write as _;
use std::fs::File;
use std::io::{self, Read};
use std::process::ExitCode;

use vstable_media_sanitizer::{
    validate_upload, MediaKind, SanitizationError, SanitizationReport, SecurityPolicy,
};

const EXIT_VALID: u8 = 0;
const EXIT_IO_OR_USAGE_ERROR: u8 = 1;
const EXIT_POLICY_REJECTED: u8 = 2;

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    run_cli(&args)
}

fn run_cli(args: &[String]) -> ExitCode {
    if args.len() != 1 {
        let msg = "usage: vstable-media-sanitizer <file-path | --stdin>";
        println!("{}", format_io_error_json("invalid_arguments", msg));
        return ExitCode::from(EXIT_IO_OR_USAGE_ERROR);
    }

    let arg = &args[0];
    if arg == "-h" || arg == "--help" {
        println!(
            "{{\"status\":\"help\",\"usage\":\"vstable-media-sanitizer <file-path | --stdin>\"}}"
        );
        return ExitCode::from(EXIT_VALID);
    }

    let policy = SecurityPolicy::default();
    let max_read = policy.max_input_bytes.saturating_add(1) as u64;

    let bytes_result = if arg == "--stdin" {
        read_bounded(io::stdin().lock(), max_read)
    } else {
        File::open(arg).and_then(|file| read_bounded(file, max_read))
    };

    let bytes = match bytes_result {
        Ok(data) => data,
        Err(err) => {
            println!(
                "{}",
                format_io_error_json("io_error", &format!("failed to read input: {err}"))
            );
            return ExitCode::from(EXIT_IO_OR_USAGE_ERROR);
        }
    };

    match validate_upload(&bytes, &policy) {
        Ok(report) => {
            println!("{}", format_report_json(&report));
            ExitCode::from(EXIT_VALID)
        }
        Err(err) => {
            println!("{}", format_rejection_json(&err));
            ExitCode::from(EXIT_POLICY_REJECTED)
        }
    }
}

fn read_bounded<R: Read>(reader: R, max_bytes: u64) -> io::Result<Vec<u8>> {
    let mut buf = Vec::new();
    reader.take(max_bytes).read_to_end(&mut buf)?;
    Ok(buf)
}

fn media_kind_str(kind: &MediaKind) -> &'static str {
    match kind {
        MediaKind::Pdf => "pdf",
        MediaKind::Png => "png",
        MediaKind::Jpeg => "jpeg",
        MediaKind::Zip => "zip",
    }
}

fn error_code_str(err: &SanitizationError) -> &'static str {
    match err {
        SanitizationError::EmptyInput => "empty_input",
        SanitizationError::InputTooLarge { .. } => "input_too_large",
        SanitizationError::InvalidFormat(_) => "invalid_format",
        SanitizationError::PolyglotDetected(_) => "polyglot_detected",
        SanitizationError::PdfInjectionDetected { .. } => "pdf_injection_detected",
        SanitizationError::DecompressionBombDetected(_) => "decompression_bomb_detected",
        SanitizationError::ImageDimensionExceeded { .. } => "image_dimension_exceeded",
        SanitizationError::PathTraversalDetected(_) => "path_traversal_detected",
        SanitizationError::NestedArchiveDetected(_) => "nested_archive_detected",
    }
}

fn format_report_json(report: &SanitizationReport) -> String {
    format!(
        "{{\"status\":\"valid\",\"kind\":\"{}\",\"input_bytes\":{},\"summary\":\"{}\"}}",
        media_kind_str(&report.kind),
        report.input_bytes,
        json_escape(&report.summary)
    )
}

fn format_rejection_json(err: &SanitizationError) -> String {
    format!(
        "{{\"status\":\"rejected\",\"error_code\":\"{}\",\"message\":\"{}\"}}",
        error_code_str(err),
        json_escape(&err.to_string())
    )
}

fn format_io_error_json(code: &str, message: &str) -> String {
    format!(
        "{{\"status\":\"error\",\"error_code\":\"{}\",\"message\":\"{}\"}}",
        json_escape(code),
        json_escape(message)
    )
}

fn json_escape(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for ch in input.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_escape_handles_special_chars() {
        let escaped = json_escape("line1\n\"quoted\"\\slash\x08");
        assert_eq!(escaped, "line1\\n\\\"quoted\\\"\\\\slash\\u0008");
    }

    #[test]
    fn format_report_produces_expected_json() {
        let report = SanitizationReport {
            kind: MediaKind::Pdf,
            input_bytes: 512,
            summary: "clean pdf".to_string(),
        };
        assert_eq!(
            format_report_json(&report),
            "{\"status\":\"valid\",\"kind\":\"pdf\",\"input_bytes\":512,\"summary\":\"clean pdf\"}"
        );
    }

    #[test]
    fn format_rejection_maps_error_code() {
        let err = SanitizationError::PdfInjectionDetected {
            indicator: "/JavaScript".to_string(),
            location: "object 4".to_string(),
        };
        let json = format_rejection_json(&err);
        assert!(json.contains("\"status\":\"rejected\""));
        assert!(json.contains("\"error_code\":\"pdf_injection_detected\""));
        assert!(json.contains("/JavaScript"));
    }

    #[test]
    fn read_bounded_stops_at_limit() {
        let data = [b'A'; 100];
        let read = read_bounded(&data[..], 16).expect("read should succeed");
        assert_eq!(read.len(), 16);
    }
}
