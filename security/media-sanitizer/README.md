# V-Stable Media Sanitizer (`vstable-media-sanitizer`)

Módulo de segurança em Rust (zero-allocation/bounded-memory) para validação estrutural e sanitização de uploads de compliance (documentos KYC/KYB, comprovantes e imagens) antes do processamento pelo backend Java/Spring Boot.

## Arquitetura do Módulo

```
security/media-sanitizer/
├── Cargo.toml          # Dependências mínimas: crc32fast, flate2 (rust_backend)
├── README.md
└── src/
    ├── lib.rs          # SecurityPolicy, MediaKind, SanitizationReport, SanitizationError e validate_upload()
    ├── pdf.rs          # Verificador anti PDF-Injection e anti polyglot/stream bomb
    ├── image_bomb.rs   # Verificador anti decompression/pixel-flood bomb (PNG, JPEG, ZIP)
    └── main.rs         # Binário CLI (arquivo ou --stdin) com saída JSON estruturada
```

### 1. `pdf.rs` — Proteção Anti PDF-Injection & Polyglot
- **Header & Trailer**: valida assinatura `%PDF-1.x` / `%PDF-2.x` no início do arquivo e presença de `%%EOF` na cauda (bloqueia arquivos polyglot ou truncados).
- **Indicadores de Execução/Injection**: inspeciona objetos e streams de sintaxe PDF (incluindo `/FlateDecode` descomprimido com limite de memória), ignorando falso-positivos em literais de texto, hyperlinks (`/URI`), objetos comprimidos (`/ObjStm`), assinaturas digitais (`/AcroForm`) e fluxos binários de imagem/fonte, enquanto bloqueia `/JavaScript`, `/JS`, `/OpenAction`, `/AA`, `/Launch`, `/SubmitForm`, `/ImportData`, `/ResetForm`, `/RichMedia`, `/GoToR`, `/GoToE`, `/EmbeddedFile`, `/Filespec` e `/XFA`.
- **Anti Stream-Bomb & Limite de Objetos**: impõe teto de objetos (`max_pdf_objects`) e descompressão limitada de streams `/FlateDecode` (`max_decompressed_bytes` e `max_compression_ratio`).

### 2. `image_bomb.rs` — Proteção Anti Image & ZIP Bomb
- **PNG (`verify_png`)**: valida assinatura, `IHDR` (dimensões máximas e teto de pixels `max_image_pixels`), CRC32 de cada chunk, presença de `IEND`, e descomprime chunks `zTXt`/`iTXt` (com limite de razão e tamanho) e `IDAT` (com teto estrito derivado das scanlines declaradas em `IHDR` + `max_decompressed_bytes`) para detectar zlib bombs sem falsos positivos em capturas de tela limpas.
- **JPEG (`verify_jpeg`)**: percorre marcadores `SOI`/`SOFn`/`EOI` validando dimensões no frame (`SOF0`–`SOF15`) antes de qualquer decodificação de pixels.
- **ZIP (`verify_image_zip`)**: analisa Local File Headers, Central Directory e EOCD sem dependências externas além de `flate2`/`crc32fast`:
  - Bloqueia **Path Traversal** (`..`, `/`, `\`, `:`).
  - Bloqueia **arquivos aninhados** (`.zip`, `.tar`, `.gz`, `.7z`, `.rar` ou magic bytes `PK\x03\x04`).
  - Descomprime entradas com limite real (`DeflateDecoder::take(remaining_budget + 1)`) para detectar cabeçalhos falsificados (`uncompressed_size` mentiroso).
  - Valida recursivamente imagens PNG/JPEG extraídas contra `verify_png` / `verify_jpeg`.

### 3. `SecurityPolicy::default()`
| Parâmetro | Valor Padrão | Objetivo |
|---|---|---|
| `max_input_bytes` | `3 MiB` (`3 * 1024 * 1024`) | Alinhado ao limite de upload de compliance em `application.yaml` |
| `max_decompressed_bytes` | `128 MiB` | Teto de expansão em memória (cobre PNG RGBA até 25 MP e streams/ZIP) |
| `max_compression_ratio` | `100` | Razão máxima descomprimido:comprimido |
| `max_image_width` / `height` | `10_000` px | Prevenção de pixel flood |
| `max_image_pixels` | `25_000_000` (25 MP) | Limite de ~100 MB RGBA descompactado |
| `max_zip_entries` | `64` | Prevenção de ZIP com milhares de entradas |
| `max_pdf_objects` | `20_000` | Prevenção de exaustão por objetos PDF |

---

## Uso da CLI & Códigos de Saída

```bash
# Validar um arquivo em disco
vstable-media-sanitizer /caminho/para/documento.pdf

# Validar via stdin (leitura limitada a max_input_bytes + 1)
cat documento.png | vstable-media-sanitizer --stdin
```

### Códigos de Saída (Exit Codes)
- `0`: Arquivo válido e aprovado pela `SecurityPolicy`.
- `2`: Arquivo rejeitado por violação da política de segurança (formato inválido, PDF injection, decompression bomb, pixel flood, path traversal, etc.).
- `1`: Erro de I/O ou argumentos inválidos.

### Exemplos de Saída JSON
Aprovado (`exit 0`):
```json
{"status":"valid","kind":"pdf","input_bytes":1024,"summary":"PDF verified"}
```

Rejeitado (`exit 2`):
```json
{"status":"rejected","error_code":"pdf_injection_detected","message":"PDF injection indicator '/JavaScript' found in object 4"}
```

---

## Como Rodar os Testes e Checks de Qualidade

### Via Docker (Recomendado — sem necessidade de toolchain Rust local)
```powershell
docker run --rm -v "C:\Users\rsaho\backend-vstable\backend-bonus-rust\security\media-sanitizer:/app" -w /app rust:1.85-slim bash -c "cargo test --all-targets && cargo clippy --all-targets -- -D warnings && cargo fmt --check"
```

### Via Cargo Local
```bash
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

---

## Integração no Backend (`BlindPayComplianceService`)

O binário `vstable-media-sanitizer` foi projetado para ser acoplado ao fluxo de upload de documentos KYC/KYB em `BlindPayComplianceService` antes do envio ao provedor externo ou armazenamento em S3:

1. **Opção A — Sidecar / Subprocesso CLI (`ProcessBuilder` + `--stdin`)**:
   - Compilar o binário estático (`cargo build --release`) e copiá-lo para a imagem Docker do backend (`Dockerfile` / `Dockerfile.lambda`) em `/usr/local/bin/vstable-media-sanitizer`.
   - No `BlindPayComplianceService`, antes de aceitar o `MultipartFile` ou payload Base64:
     - Invocar `/usr/local/bin/vstable-media-sanitizer --stdin` com timeout curto (ex.: 2–5 s).
     - Escrever os bytes do upload no `OutputStream` do processo e fechar o stream.
     - Ler o `exitCode`:
       - `0`: prosseguir com o fluxo de compliance.
       - `2`: rejeitar imediatamente com `400 Bad Request` / `422 Unprocessable Entity`, logando `error_code` e `message` do JSON (sem expor detalhes internos ao cliente final).
       - `1` ou timeout: falhar fechado (*fail-closed*) com `500` / `503`.
   - **Vantagens**: isolamento de memória em processo separado (se um parser sofrer panic/OOM, a JVM não cai), zero acoplamento JNI e compatível com AWS Lambda container image.

2. **Opção B — AWS Lambda Layer / FFI (JNI)**:
   - Expor `validate_upload` via `cdylib` com assinatura C ABI (`extern "C"`) ou empacotar o binário em uma Lambda Layer `/opt/bin/vstable-media-sanitizer` invocada pela função Java.
