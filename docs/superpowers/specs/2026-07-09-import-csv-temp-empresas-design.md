# Importación masiva de empresas desde CSV (módulo temporal)

> Fuera del alcance del MVP según `PRD-backend.md` §"Fuera del MVP" y `reglas_negocio.md` §"Import masivo de cartera desde Excel" (post-MVP). Se implementa igual, a pedido explícito, como solución **desechable**: cubre solo empresas y solo 3 columnas. Se reemplazará por el módulo real de importación cuando llegue esa fase, y este código se borrará por completo.

## Contexto

Hoy la creación de empresas es 1 a 1 vía `POST /empresas`. Se necesita una vía rápida para cargar muchas empresas desde un CSV con columnas RUC, Razón Social y Segmento, sin construir el módulo de importación definitivo (que cubrirá más entidades y probablemente Excel).

## Ubicación y aislamiento

Carpeta nueva, **fuera de `domain/`**, hermana de `domain/` y `shared/`, para que quede claro que es temporal y se pueda borrar entera sin tocar ningún módulo real:

```
src/main/kotlin/pe/quantum/crm/importcsvtemp/
├── ImportCsvTempController.kt
├── ImportCsvTempService.kt        (interfaz)
├── ImportCsvTempServiceImpl.kt
└── dto/ImportCsvTempDtos.kt
```

Accede a empresas **únicamente vía `EmpresaService.crear(...)`** (interfaz pública del módulo `empresas`). Nunca toca `EmpresaRepository` ni la entidad `Empresa` directamente — respeta la regla de aislamiento entre módulos (CLAUDE.md regla 12) y reutiliza toda la validación existente (formato de RUC, RUC duplicado, defaults de `estado_cartera`, etc.) sin duplicarla.

Alternativas descartadas:
- Método `importarCsv` dentro de `EmpresaServiceImpl`: ensuciaría el módulo real con código desechable.
- Escritura directa a `EmpresaRepository` desde el módulo nuevo: más rápido pero rompe el aislamiento entre módulos y duplicaría validaciones de negocio ya existentes en `crear()`.

## Endpoint

`POST /api/v1/import-csv-temp/empresas` — `multipart/form-data`, campo `file`.

Se usa un prefijo de ruta distinto a `/empresas/import` (reservado en `contrato_api.md` §"empresas" para el futuro módulo real desde Excel) para no chocar ni generar confusión con ese contrato futuro.

- Autenticado, sin `@PreAuthorize` adicional — mismo permiso que `POST /empresas` (cualquier rol logueado puede crear empresas según `matriz_permisos.md` §2.2).
- No se documenta en `contrato_api.md`: es temporal y no es parte del contrato que consume el frontend estable.

### Formato del CSV

- UTF-8, separador coma, primera fila = cabecera (se descarta sin validar su contenido).
- Columnas **posicionales**, en este orden: `0=RUC`, `1=Razón Social`, `2=Segmento`.
- Parser CSV propio y mínimo (sin dependencia nueva), soporta campos entre comillas con comas internas (ej. `"Empresa S.A., Sucursal Lima"`).
- Límite de 1000 filas de datos por archivo. Si se excede, o el archivo está vacío / sin cabecera / ilegible → `400`.

### Respuesta (`200`, siempre que el archivo en sí sea válido)

```json
{
  "success": true,
  "data": {
    "totalFilas": 50,
    "creadas": 47,
    "conError": 3,
    "detalle": [
      { "fila": 12, "ruc": "1234567890", "razonSocial": null, "estado": "error", "motivo": "RUC debe tener 11 dígitos" },
      { "fila": 23, "ruc": "20123456789", "razonSocial": "Acme SAC", "estado": "error", "motivo": "El RUC ya está registrado" },
      { "fila": 31, "ruc": "20999999999", "razonSocial": "Beta SRL", "estado": "creada", "motivo": null }
    ]
  }
}
```

`fila` es el número de línea del CSV (1 = cabecera, 2 = primera fila de datos, etc.).

## Flujo por fila (mejor esfuerzo)

Por cada fila de datos:

1. Parsear las 3 columnas; si faltan columnas → error "fila incompleta".
2. Validar RUC: no vacío, exactamente 11 dígitos (mismo patrón que `CrearEmpresaRequest.ruc`).
3. Validar Razón Social: no vacía.
4. Parsear Segmento: `Segmento.valueOf(valor.trim().lowercase())`; si no matchea ningún valor del enum → error `"segmento desconocido: {valor}"`.
5. Si todo válido: `empresaService.crear(CrearEmpresaRequest(ruc = ruc, razonSocial = razonSocial, segmentos = listOf(segmento)), usuarioActual)`.
6. Cualquier excepción de `crear()` (incluye `RucDuplicadoException`) se captura y la fila se registra como error con el mensaje de la excepción como `motivo`, sin abortar el resto del archivo.

El método de importación **no** lleva `@Transactional` a nivel de archivo completo. Cada fila corre en la transacción propia de `EmpresaService.crear()`, que commitea antes de procesar la siguiente fila. Esto habilita el "mejor esfuerzo" (una fila con error no revierte las demás) y de paso detecta RUC repetido **dentro del mismo CSV** sin lógica adicional: si la fila 5 y la fila 20 traen el mismo RUC, la fila 20 falla por `RucDuplicadoException` porque la fila 5 ya se commiteó.

Campos que la entidad `Empresa` soporta pero el CSV no trae (`idVendedor`, `distrito`, `origenLead`, etc.) quedan con los defaults de `EmpresaService.crear()` (según `visibilidadRestringida` del usuario que sube el archivo).

## Testing (TDD, `TESTING-backend.md`)

- Fila válida → se crea la empresa (verificar con `EmpresaService`/repositorio real, patrón Testcontainers existente en el módulo empresas).
- RUC con menos de 11 dígitos → fila en `error`, resto del archivo se procesa igual.
- RUC ya existente en BD → fila en `error` con motivo de duplicado.
- Dos filas del mismo CSV con el mismo RUC → primera `creada`, segunda `error` por duplicado.
- Segmento desconocido (ej. `"corporativo"`, que no es un valor del enum) → fila en `error`.
- Razón Social con coma dentro de comillas → se parsea completa, no se corta en la coma.
- Archivo vacío o sin filas de datos → `400`.
- Archivo con más de 1000 filas de datos → `400`.
- Usuario no autenticado → `401` (verificado por la cadena de seguridad estándar, sin lógica propia del módulo).

## Fuera de alcance

- No se documenta en `contrato_api.md` ni `matriz_permisos.md` — es un endpoint temporal, no parte del contrato estable con el frontend.
- No soporta Excel, solo CSV.
- No soporta más de un segmento por fila.
- No soporta otras entidades (contactos, oportunidades, etc.) — solo empresas.
- No tiene endpoint de "plantilla descargable" ni preview antes de confirmar — sube y procesa en una sola llamada.
- Este módulo se elimina por completo cuando se construya el módulo de importación definitivo (fuera del alcance de este ciclo).
