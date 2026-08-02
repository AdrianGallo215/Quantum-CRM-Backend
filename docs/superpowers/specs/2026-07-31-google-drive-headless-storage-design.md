# Headless Storage en Google Drive — diseño

Fecha: 2026-07-31 · Rama: `feature/b08-auth-endpoints`

## Problema

Los documentos del CRM (fichas de venta, contratos, sustentos) deben vivir en Google
Drive, no en el servidor. El servidor no debe gastar disco ni RAM proporcional al
tamaño del archivo: actúa como tubería, no como almacén.

## Hallazgo que define la arquitectura

Las cuentas de servicio **no tienen cuota de almacenamiento y no pueden ser dueñas de
archivos**. Si el Service Account sube un archivo a una carpeta de *Mi unidad*
compartida con él, Drive responde `403 storageQuotaExceeded`. La trampa: crear
carpetas sí funciona (pesan 0 bytes), así que el fallo aparece recién en la primera
subida real.

Por eso el destino es una **unidad compartida** (`ROOT_DRIVE_FOLDER_ID` empieza en
`0A`), con el Service Account como Administrador de contenido. Toda llamada al API
lleva `supportsAllDrives=true`; sin ese flag el SDK ni siquiera ve la unidad.

## Arquitectura

```
POST /api/v1/oportunidades/{id}/archivos   (multipart/form-data)
   │
   │  DriveUploadMultipartResolver  → declara "no es multipart" para esta ruta,
   │                                   así Tomcat nunca vuelca a disco
   ▼
OportunidadArchivoController
   │  JakartaServletFileUpload.getItemIterator(request)  → InputStream crudo
   ▼
DriveStorageService.subirArchivo(stream, nombre, mimeType, parentId)
   │  InputStreamContent SIN setLength() + upload resumable con chunk de 5 MB
   ▼
Google Drive (unidad compartida)
```

El archivo nunca toca el disco y la RAM queda acotada al chunk (5 MB por defecto),
sea el archivo de 1 MB o de 1 GB.

### Módulo `pe.quantum.crm.integracion.drive`

Vive fuera de `domain/` porque es infraestructura compartida, no un dominio de
negocio. Los módulos de dominio lo consumen por su interfaz pública
`DriveStorageService`, respetando la regla 12 de CLAUDE.md.

| Archivo | Responsabilidad |
|---|---|
| `DriveProperties` | `@ConfigurationProperties("app.drive")` — credenciales, raíz, chunk, timeouts |
| `DriveConfig` | Construye el bean `Drive` desde el JSON en base64 |
| `DriveStorageService` | Interfaz: `crearCarpeta`, `subirArchivo` |
| `DriveStorageServiceImpl` | Implementación contra el SDK |
| `DriveArchivoSubido` | DTO de retorno (id, nombre, url, tamaño) |
| `DriveException` | Error de integración → HTTP 502 en `GlobalExceptionHandler` |

### Credenciales

El JSON del Service Account **nunca se lee desde disco en runtime ni se commitea**.
Se codifica entero en base64 (línea única) y viaja en
`GOOGLE_DRIVE_CREDENTIALS_BASE64`. `.gitignore` cubre el patrón del archivo JSON.

### Modelo de datos (V35)

`empresas.drive_folder_id` y `oportunidades.drive_folder_id`, ambos `VARCHAR(255)`
NULLable. Los administra el backend; son de solo lectura para el cliente.

No confundir con `empresas.file_drive`, que ya existía: es una URL suelta que el
usuario pega a mano. Se mantiene intacta.

NULLable porque las filas anteriores a V35 no tienen carpeta; se crean bajo demanda
en la primera subida (`asegurarCarpetaOportunidad`).

### Ciclo de vida de las carpetas

- **Al crear una empresa:** carpeta `{ruc} - {razonSocial}` bajo la raíz.
- **Al crear una oportunidad:** subcarpeta `OP-{id} - {modelo}` dentro de la carpeta
  de su empresa. Si la empresa aún no tiene carpeta, se crea primero.
- **Bajo demanda:** si al subir un archivo la oportunidad no tiene carpeta, se crea
  en ese momento. Cubre las filas viejas y cualquier hueco.

### Decisión: creación bloqueante

Por decisión explícita del usuario, la carpeta se crea **dentro de la transacción**
de creación. Si Drive falla, la creación de la empresa u oportunidad falla y hace
rollback: nunca queda una fila sin carpeta por un error de Drive.

Contrapartida aceptada: una caída de Drive impide crear empresas y oportunidades, y
la conexión a la BD queda tomada durante la llamada HTTP externa. Se acota con
timeouts explícitos (10 s de conexión, 120 s de lectura) para que una conexión
colgada no retenga el pool indefinidamente.

## Contrato de API

```
POST /api/v1/oportunidades/{id}/archivos
Content-Type: multipart/form-data     campo: file
201 → { "data": { "id", "nombre", "url", "tamano_bytes", "mime_type" } }
```

Errores: `404` oportunidad inexistente o ajena (IDOR → 404, nunca 403) ·
`400` sin archivo o vacío · `413` excede `DRIVE_MAX_FILE_SIZE_BYTES` ·
`502` Drive no responde.

Permisos: los mismos que ver la oportunidad. Un vendedor solo sube a las suyas.

`drive_folder_id` se expone como campo de solo lectura en los DTO de detalle de
empresa y oportunidad, para que el frontend arme el enlace a la carpeta.

## Tests

- `DriveStorageServiceImplTest` — el SDK mockeado: verifica `supportsAllDrives`,
  mimeType de carpeta, chunk configurado, `setRetrySupported(false)`, y que un
  `IOException` se traduzca a `DriveException`.
- `EmpresaServiceImplTest` / `OportunidadServiceImplTest` — la carpeta se crea con
  el padre correcto y su ID se persiste; un fallo de Drive propaga y no persiste.
- `OportunidadArchivoControllerTest` — 201 en el camino feliz; 404 ajena; 400 vacío.

## Fuera de alcance

Borrado y renombrado de carpetas al eliminar o renombrar recursos · descarga y
listado de archivos desde el CRM · permisos por archivo en Drive · migración de los
`file_drive` existentes.
