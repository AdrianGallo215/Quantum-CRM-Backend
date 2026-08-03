# Creación explícita y backfill de carpetas de Drive — diseño

Fecha: 2026-07-31 · Rama: `feature/b08-auth-endpoints`

## Problema

Hoy la carpeta de Drive de una empresa u oportunidad solo nace en dos momentos: al
crear el registro (desde que existe la integración) o al subir el primer archivo.
Eso deja dos huecos:

1. **No hay forma de crear la carpeta a propósito.** El usuario quiere un botón
   "Crear File del Cliente" que la cree sin tener que subir un documento primero.
2. **Todo lo anterior a la integración tiene `drive_folder_id = null`.** Empresas y
   oportunidades creadas antes de esta funcionalidad no tienen carpeta y no la
   tendrán hasta que alguien las toque una por una.

## Lo que YA funciona y no se toca

Verificado en el código antes de diseñar, no asumido:

- Al crear una oportunidad se crea su subcarpeta **dentro** de la carpeta de la
  empresa (`OportunidadServiceImpl.crear`).
- Si esa empresa no tenía carpeta, se crea primero la de la empresa y luego la de
  la oportunidad dentro (`empresaService.asegurarCarpetaDrive(empresa.id)`). Ambos
  casos tienen test.
- El drag-and-drop del frontend **no requiere cambios de backend**: arrastrar un
  archivo solo cambia cómo el navegador arma el `FormData`; el endpoint de subida
  ya acepta cualquier `multipart/form-data` con campo `file`.

Este diseño NO modifica `EmpresaServiceImpl.crear()` ni
`OportunidadServiceImpl.crear()`, ni el esquema de base de datos.

## Parte 1 — Creación explícita, por registro

```
POST /api/v1/empresas/:id/carpeta-drive
POST /api/v1/oportunidades/:id/carpeta-drive
```

Body vacío. Respuesta 200: `{ "data": { "drive_folder_id": "1AbC..." } }`.

No hay lógica nueva de creación: ambos delegan en el `asegurarCarpetaDrive(id,
usuario)` que ya existe y ya usa la subida de archivos. Son **idempotentes** — si
ya hay carpeta la devuelven sin tocar Drive, así el frontend puede llamarlos sin
verificar antes.

Errores: `404 NO_ENCONTRADO` (ajena o inexistente, IDOR) · `502
DRIVE_NO_DISPONIBLE` / `DRIVE_SIN_CUOTA`.

Permisos: los mismos que ver el registro (un vendedor solo sobre los suyos).

**Frontend:** el botón se **oculta** (no se deshabilita) cuando `drive_folder_id`
ya viene distinto de `null` en el detalle. Ese campo ya se expone en
`GET /empresas/:id` y `GET /oportunidades/:id`.

### Reorganización de los controllers de Drive

`EmpresaArchivoController` y `OportunidadArchivoController` tienen hoy un
`@RequestMapping` fijo a `.../archivos`, que no deja colgar otra ruta hermana. Se
cambia el mapeo de clase a `/api/v1/{entidad}/{id}` y las rutas pasan al método
(`/archivos`, `/carpeta-drive`). Se renombran a `EmpresaDriveController` y
`OportunidadDriveController`, que describe mejor lo que hacen ahora.

Las URLs públicas de `GET`/`POST .../archivos` **no cambian**: el frontend ya
integrado sigue funcionando sin tocar nada.

## Parte 2 — Backfill masivo

```
POST /api/v1/mantenimiento/carpetas-drive
POST /api/v1/mantenimiento/carpetas-drive?tamano_lote=25
```

Solo rol `admin`. Sin `tamano_lote` procesa **todo lo pendiente en un solo
llamado** — es el comportamiento por defecto, decidido porque el volumen actual es
de pocas decenas de registros. El parámetro queda como válvula de escape si algún
día el volumen crece.

Respuesta 200:

```json
{
  "data": {
    "empresas_procesadas": 12,
    "oportunidades_procesadas": 30,
    "errores": [
      { "entidad": "empresa", "id": 7, "motivo": "Google Drive no pudo crear la carpeta '...'" }
    ],
    "pendientes_restantes": 0
  }
}
```

`pendientes_restantes` permite al llamador saber si hace falta repetir (solo pasa
si se usó `tamano_lote`, o si hubo errores).

### Cómo procesa

Primero empresas, luego oportunidades. El orden importa poco porque
`asegurarCarpetaDrive` de oportunidad ya encadena la carpeta de su empresa si
falta, pero empezar por empresas evita trabajo redundante.

**Cada registro se crea y persiste en su propia transacción**, no todos al final.
Si la llamada se corta a la mitad, lo ya procesado queda guardado y repetir el
endpoint retoma donde quedó, sin duplicar carpetas.

Esto obliga a una decisión de diseño concreta: **el bucle vive en el coordinador,
no dentro de los servicios de dominio**. Si `EmpresaServiceImpl` iterara y llamara
a su propio `asegurarCarpetaDrive`, la auto-invocación saltaría el proxy de Spring
y las transacciones por registro no existirían — todo correría en una sola
transacción y un fallo al final revertiría lo anterior. Llamando desde fuera, cada
invocación pasa por el proxy y abre su propia transacción.

Un error en un registro no aborta el resto: se acumula en `errores` y el bucle
sigue.

### Ubicación del módulo

Nuevo paquete `pe.quantum.crm.mantenimiento`, fuera de `domain/`, siguiendo el
precedente de `importcsvtemp` (que también vive fuera de `domain/` y depende de
`EmpresaService`). Depende solo de las interfaces públicas de servicio, nunca de
repositorios ni entidades ajenas (CLAUDE.md regla 12).

Poner el coordinador dentro de `integracion/drive` se descartó: invertiría la
dirección de dependencias actual (`domain` → `integracion`) y crearía un ciclo a
nivel de paquete.

| Archivo | Responsabilidad |
|---|---|
| `mantenimiento/CarpetasDriveBackfillController` | `POST /api/v1/mantenimiento/carpetas-drive`, admin-only |
| `mantenimiento/CarpetasDriveBackfillService` | Bucle, aislamiento de errores, conteos |
| `mantenimiento/dto/BackfillCarpetasDto` | Respuesta con conteos y errores |

### Métodos nuevos en las interfaces de dominio

- `EmpresaService.idsSinCarpetaDrive(): List<Long>`
- `OportunidadService.idsSinCarpetaDrive(): List<Long>`
- `OportunidadService.asegurarCarpetaDrive(id: Long): String` — sobrecarga sin
  usuario, para jobs de sistema. Réplica de la que `EmpresaService` ya tiene.

Repositorios: `findByDriveFolderIdIsNull()` derivado por nombre en
`EmpresaRepository` y `OportunidadRepository`, proyectando solo los ids.

## Testing

- `EmpresaServiceImplTest` / `OportunidadServiceImplTest`: `idsSinCarpetaDrive`
  devuelve solo los que tienen la columna nula; la sobrecarga sin usuario de
  oportunidad crea la carpeta sin verificar visibilidad.
- `CarpetasDriveBackfillServiceTest`: procesa empresas antes que oportunidades;
  **un fallo en un registro no detiene el resto** y queda listado en `errores`;
  los conteos cuadran; `tamano_lote` limita y reporta `pendientes_restantes`.
- Tests de los controllers: 200 idempotente cuando ya hay carpeta, 200 creando
  cuando no, 404 en registro ajeno, y **403 en el backfill para un rol no admin**.
- Smoke test manual contra Drive real antes de dar por cerrado, como se hizo con
  todo lo anterior de esta integración. Se borra después de ejecutarlo.

## Fuera de alcance

Borrado o renombrado de carpetas · reintentos automáticos de los registros que
fallen en el backfill (se repite el endpoint a mano) · programar el backfill
periódicamente · UI del backfill (es admin-only, se dispara con un request).
