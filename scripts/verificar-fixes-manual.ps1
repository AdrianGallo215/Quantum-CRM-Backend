# =============================================================================
# Verificacion manual de los fixes de code-review (docs/code-review-pendientes.md)
# PowerShell 5.1. Requiere: app corriendo en local (COOKIE_SECURE=false en .env),
# Postgres real, y un usuario ADMIN existente para loguearse.
#
# Crea datos de prueba reales (prefijo ZZ_QA_) en tu Postgres local y, si Drive
# esta configurado con credenciales de produccion, TAMBIEN crea carpetas reales
# en el Drive compartido. Revisa la seccion de LIMPIEZA al final.
#
# Uso: pega bloque por bloque, o el archivo completo de una sola vez.
# =============================================================================

# ── BLOQUE 0: Configuracion y helpers ───────────────────────────────────────

$baseUrl = "http://localhost:8080"
$adminEmail = "admin@quantum.pe"
$adminPassword = "quantum123"
$ts = Get-Date -Format "yyyyMMddHHmmss"

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, $Session)
    $uri = "$baseUrl$Path"
    try {
        if ($Body) {
            $json = $Body | ConvertTo-Json -Depth 10
            $resp = Invoke-WebRequest -Uri $uri -Method $Method -Body $json -ContentType "application/json" -WebSession $Session -UseBasicParsing
        } else {
            $resp = Invoke-WebRequest -Uri $uri -Method $Method -WebSession $Session -UseBasicParsing
        }
        return [PSCustomObject]@{ StatusCode = $resp.StatusCode; Body = ($resp.Content | ConvertFrom-Json) }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        $errBody = $null
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $errBody = $reader.ReadToEnd() | ConvertFrom-Json
        } catch {}
        return [PSCustomObject]@{ StatusCode = $statusCode; Body = $errBody }
    }
}

function Check {
    param([string]$Nombre, $Resultado, [int]$Esperado)
    $ok = $Resultado.StatusCode -eq $Esperado
    if ($ok) {
        Write-Host "[OK]    $Nombre (esperado $Esperado, obtuvo $($Resultado.StatusCode))" -ForegroundColor Green
    } else {
        Write-Host "[FALLO] $Nombre (esperado $Esperado, obtuvo $($Resultado.StatusCode))" -ForegroundColor Red
        if ($Resultado.Body) { Write-Host "        -> $($Resultado.Body | ConvertTo-Json -Compress)" -ForegroundColor Yellow }
    }
    return $Resultado
}

Write-Host "`n=== Datos de prueba con sufijo: $ts ===" -ForegroundColor Cyan

# ── BLOQUE 1: Login como admin ──────────────────────────────────────────────

$loginBody = @{ email = $adminEmail; password = $adminPassword }
try {
    Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/login" -Method Post -Body ($loginBody | ConvertTo-Json) -ContentType "application/json" -SessionVariable session | Out-Null
    $session = $session  # -SessionVariable crea la variable en el scope actual
    Write-Host "[OK]    Login admin, sesion obtenida" -ForegroundColor Green
} catch {
    Write-Host "[FALLO] Login admin: $_" -ForegroundColor Red
    Write-Host "Revisa `$adminEmail/`$adminPassword arriba, y que la app este corriendo en $baseUrl" -ForegroundColor Yellow
}

# ── BLOQUE 2: [A1] GET /contactos?q=... ─────────────────────────────────────

Write-Host "`n--- A1: busqueda de contactos ---" -ForegroundColor Cyan
Check "GET /contactos?q=a" (Invoke-Api GET "/api/v1/contactos?q=a&page=1&per_page=5" -Session $session) 200

# ── BLOQUE 3: [A2] PUT /empleados/:id sin validar ───────────────────────────

Write-Host "`n--- A2: validacion de PUT empleados ---" -ForegroundColor Cyan

$empleadoPrueba = @{
    nombres   = "ZZ_QA_Prueba"
    apellidos = "Temporal"
    email     = "zz.qa.$ts@quantum.pe"
    password  = "PasswordQA12345"
    rol       = "vendedor"
}
$rEmpleado = Check "POST /empleados (crear empleado de prueba)" (Invoke-Api POST "/api/v1/empleados" $empleadoPrueba -Session $session) 201
$idEmpleadoPrueba = $rEmpleado.Body.data.id
Write-Host "  id_empleado_prueba = $idEmpleadoPrueba"

if ($idEmpleadoPrueba) {
    Check "PUT /empleados/:id con email invalido -> 400" (Invoke-Api PUT "/api/v1/empleados/$idEmpleadoPrueba" @{ email = "noesuncorreo" } -Session $session) 400
    $rPut = Check "PUT /empleados/:id parcial (solo nombres) -> 200" (Invoke-Api PUT "/api/v1/empleados/$idEmpleadoPrueba" @{ nombres = "ZZ_QA_Editado" } -Session $session) 200
    if ($rPut.Body.data.apellidos -eq "Temporal") {
        Write-Host "[OK]    El apellido no se borro con la edicion parcial" -ForegroundColor Green
    } else {
        Write-Host "[FALLO] El apellido se perdio en la edicion parcial: $($rPut.Body.data.apellidos)" -ForegroundColor Red
    }
}

# ── BLOQUE 4: [C2 + D1] Crear empresa de prueba, PUT parcial, Drive ─────────

Write-Host "`n--- C2 + D1: empresa de prueba ---" -ForegroundColor Cyan

# Necesita un vendedor activo real para poder crear la oportunidad despues.
$rVendedores = Invoke-Api GET "/api/v1/empleados?rol=vendedor&activo=true" -Session $session
$idVendedorPrueba = $rVendedores.Body.data[0].id
if (-not $idVendedorPrueba) {
    Write-Host "[AVISO] No hay ningun vendedor activo en el sistema. Los bloques 5, 6 y 7 (C3, B3) se van a saltar." -ForegroundColor Yellow
}

$rucPrueba = "99" + (Get-Random -Minimum 100000000 -Maximum 999999999)
$empresaPrueba = @{
    ruc          = $rucPrueba
    razon_social = "ZZ_QA_Empresa_$ts"
    segmentos    = @("urbano")
    id_vendedor  = $idVendedorPrueba
}
$rEmpresa = Check "POST /empresas (Drive real: crea carpeta)" (Invoke-Api POST "/api/v1/empresas" $empresaPrueba -Session $session) 201
$idEmpresaPrueba = $rEmpresa.Body.data.id
Write-Host "  id_empresa_prueba = $idEmpresaPrueba"
if ($rEmpresa.Body.data.drive_folder_id) {
    Write-Host "[OK]    drive_folder_id presente: $($rEmpresa.Body.data.drive_folder_id)" -ForegroundColor Green
} else {
    Write-Host "[FALLO] drive_folder_id vino vacio" -ForegroundColor Red
}

if ($idEmpresaPrueba) {
    Check "PUT /empresas/:id parcial sin ruc -> 200" (Invoke-Api PUT "/api/v1/empresas/$idEmpresaPrueba" @{ notas = "prueba QA" } -Session $session) 200

    # D1: pedir la carpeta de nuevo no debe crear una segunda
    $rCarpeta1 = Invoke-Api POST "/api/v1/empresas/$idEmpresaPrueba/carpeta-drive" -Session $session
    $rCarpeta2 = Check "POST carpeta-drive repetido -> misma carpeta" (Invoke-Api POST "/api/v1/empresas/$idEmpresaPrueba/carpeta-drive" -Session $session) 200
    if ($rCarpeta1.Body.data.drive_folder_id -eq $rCarpeta2.Body.data.drive_folder_id) {
        Write-Host "[OK]    No se duplico la carpeta" -ForegroundColor Green
    } else {
        Write-Host "[FALLO] Se creo una carpeta distinta la segunda vez" -ForegroundColor Red
    }
}

# ── BLOQUE 5: [C3] Contacto + vinculo + oportunidad, PUT del rol ────────────

Write-Host "`n--- C3: contacto vinculado a oportunidad ---" -ForegroundColor Cyan

$idContactoPrueba = $null
$idOportunidadPrueba = $null

if ($idEmpresaPrueba -and $idVendedorPrueba) {
    $contactoPrueba = @{
        nombres    = "ZZ_QA_Contacto"
        apellidos  = "Prueba"
        id_empresa = $idEmpresaPrueba
        tlf_1      = "999888777"
    }
    $rContacto = Check "POST /contactos" (Invoke-Api POST "/api/v1/contactos" $contactoPrueba -Session $session) 201
    $idContactoPrueba = $rContacto.Body.data.id
    Write-Host "  id_contacto_prueba = $idContactoPrueba"

    if ($idContactoPrueba) {
        Check "GET /contactos?q=ZZ_QA_Contacto -> ya no da 500" (Invoke-Api GET "/api/v1/contactos?q=ZZ_QA_Contacto" -Session $session) 200

        $rModelos = Invoke-Api GET "/api/v1/modelos" -Session $session
        $idModeloPrueba = $rModelos.Body.data[0].id
        if (-not $idModeloPrueba) {
            Write-Host "[AVISO] No hay modelos en el sistema. Se salta la creacion de la oportunidad." -ForegroundColor Yellow
        } else {
            $oportunidadPrueba = @{
                id_empresa = $idEmpresaPrueba
                id_modelo  = $idModeloPrueba
                cantidad   = 1
                contactos  = @(@{ id_contacto = $idContactoPrueba; rol_en_oportunidad = "Titular" })
            }
            $rOportunidad = Check "POST /oportunidades" (Invoke-Api POST "/api/v1/oportunidades" $oportunidadPrueba -Session $session) 201
            $idOportunidadPrueba = $rOportunidad.Body.data.id
            Write-Host "  id_oportunidad_prueba = $idOportunidadPrueba"

            if ($idOportunidadPrueba) {
                # Fix C3: el body exacto del contrato, SIN id_contacto
                Check "PUT contactos/:id con body del contrato (sin id_contacto) -> 200" `
                    (Invoke-Api PUT "/api/v1/oportunidades/$idOportunidadPrueba/contactos/$idContactoPrueba" @{ rol_en_oportunidad = "Aprobador" } -Session $session) 200
            }
        }
    }
} else {
    Write-Host "[SALTADO] Falta empresa o vendedor de prueba." -ForegroundColor Yellow
}

# ── BLOQUE 6: [C1] Formato de fechas ────────────────────────────────────────

Write-Host "`n--- C1: formato de fechas (Z en timestamps, sin Z en fechas de calendario) ---" -ForegroundColor Cyan

if ($idOportunidadPrueba) {
    # Fijamos una fecha de calendario para poder revisar su formato
    Invoke-Api PUT "/api/v1/oportunidades/$idOportunidadPrueba" @{ fecha_cierre_estimado = "2026-12-31" } -Session $session | Out-Null
    $rDetalle = Invoke-Api GET "/api/v1/oportunidades/$idOportunidadPrueba" -Session $session
    $d = $rDetalle.Body.data

    function Chequear-Formato($nombre, $valor, $debeTenerZ) {
        if ($null -eq $valor) { Write-Host "  ($nombre es null, no se puede verificar)" -ForegroundColor Yellow; return }
        $tieneZ = $valor.ToString().EndsWith("Z")
        if ($tieneZ -eq $debeTenerZ) {
            Write-Host "[OK]    $nombre = $valor" -ForegroundColor Green
        } else {
            Write-Host "[FALLO] $nombre = $valor (se esperaba Z=$debeTenerZ)" -ForegroundColor Red
        }
    }
    Chequear-Formato "created_at" $d.created_at $true
    Chequear-Formato "entrada_etapa_actual" $d.entrada_etapa_actual $true
    Chequear-Formato "fecha_cierre_estimado" $d.fecha_cierre_estimado $false
} else {
    Write-Host "[SALTADO] No hay oportunidad de prueba." -ForegroundColor Yellow
}

# ── BLOQUE 7: [B3] dcto con mas de 2 decimales ──────────────────────────────

Write-Host "`n--- B3: escala de dcto ---" -ForegroundColor Cyan

if ($idOportunidadPrueba) {
    Check "PUT oportunidad con dcto=2.994 (3 decimales) -> 400" `
        (Invoke-Api PUT "/api/v1/oportunidades/$idOportunidadPrueba" @{ dcto = 2.994 } -Session $session) 400
} else {
    Write-Host "[SALTADO] No hay oportunidad de prueba." -ForegroundColor Yellow
}

# ── BLOQUE 8: [B1] Comparacion reportes/ventas vs inicio (informativo) ─────

Write-Host "`n--- B1: reportes/ventas vs inicio (solo informativo, revisa a ojo) ---" -ForegroundColor Cyan
Write-Host "Esto solo tiene sentido si ya tienes oportunidades reales en estado 'facturado'."

$hoy = Get-Date -Format "yyyy-MM-dd"
$haceUnAnio = (Get-Date).AddYears(-1).ToString("yyyy-MM-dd")
$rVentas = Invoke-Api GET "/api/v1/reportes/ventas?fecha_desde=$haceUnAnio&fecha_hasta=$hoy" -Session $session
$rInicio = Invoke-Api GET "/api/v1/inicio" -Session $session
Write-Host "  /reportes/ventas (ultimo anio):"
$rVentas.Body.data | ConvertTo-Json -Depth 5 | Write-Host
Write-Host "  /inicio:"
$rInicio.Body.data | ConvertTo-Json -Depth 5 | Write-Host

# ── BLOQUE 9: [D2] Import CSV + backfill de carpetas (multipart) ───────────

Write-Host "`n--- D2: import CSV sin Drive por fila ---" -ForegroundColor Cyan
Write-Host "Si este bloque falla por multipart en PS 5.1, sube el CSV a mano con Postman en su lugar."

try {
    Add-Type -AssemblyName System.Net.Http
    $httpClient = New-Object System.Net.Http.HttpClient
    $cookieHeader = ($session.Cookies.GetCookies([Uri]$baseUrl) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; "
    $httpClient.DefaultRequestHeaders.Add("Cookie", $cookieHeader)

    $rucCsv1 = "98" + (Get-Random -Minimum 100000000 -Maximum 999999999)
    $rucCsv2 = "97" + (Get-Random -Minimum 100000000 -Maximum 999999999)
    $csvContent = "ruc;razon_social;segmento`n$rucCsv1;ZZ_QA_ImportCsv1_$ts;urbano`n$rucCsv2;ZZ_QA_ImportCsv2_$ts;urbano`n"
    $csvBytes = [System.Text.Encoding]::UTF8.GetBytes($csvContent)

    $content = New-Object System.Net.Http.MultipartFormDataContent
    $fileContent = New-Object System.Net.Http.ByteArrayContent(,$csvBytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/csv")
    $content.Add($fileContent, "file", "zz_qa_import.csv")

    $httpResult = $httpClient.PostAsync("$baseUrl/api/v1/import-csv-temp/empresas", $content).Result
    $importBody = $httpResult.Content.ReadAsStringAsync().Result | ConvertFrom-Json

    if ($httpResult.StatusCode -eq 200) {
        Write-Host "[OK]    Import CSV: $($importBody.data.creadas) creadas, $($importBody.data.carpetas_drive_pendientes) carpetas pendientes" -ForegroundColor Green
        if ($importBody.data.detalle[0].estado -and -not ($importBody.data | Get-Member -Name "drive_folder_id")) {
            Write-Host "[OK]    La respuesta no trae drive_folder_id (esperado: se crea despues via backfill)" -ForegroundColor Green
        }
        if ($importBody.data.carpetas_drive_pendientes -gt 0) {
            $rBackfill = Check "POST /mantenimiento/carpetas-drive" (Invoke-Api POST "/api/v1/mantenimiento/carpetas-drive" -Session $session) 200
            Write-Host "  Backfill: $($rBackfill.Body.data | ConvertTo-Json -Compress)"
        }
    } else {
        Write-Host "[FALLO] Import CSV: status $($httpResult.StatusCode)" -ForegroundColor Red
        Write-Host $importBody
    }
} catch {
    Write-Host "[AVISO] El bloque de import CSV fallo por un tema de PowerShell/multipart, no del backend: $_" -ForegroundColor Yellow
    Write-Host "        Prueba este endpoint directamente en Postman: POST $baseUrl/api/v1/import-csv-temp/empresas (form-data, campo 'file')"
}

# ── BLOQUE 10 (OPCIONAL/AVANZADO): [B2] PoliticaDescuento con rol 'otro' ───

Write-Host "`n--- B2 (opcional): PoliticaDescuento fail-closed con rol 'otro' ---" -ForegroundColor Cyan

$empleadoOtro = @{
    nombres   = "ZZ_QA_RolOtro"
    apellidos = "Prueba"
    email     = "zz.qa.otro.$ts@quantum.pe"
    password  = "PasswordQA12345"
    rol       = "otro"
}
$rOtro = Check "POST /empleados rol=otro" (Invoke-Api POST "/api/v1/empleados" $empleadoOtro -Session $session) 201
$idEmpleadoOtro = $rOtro.Body.data.id

if ($idEmpleadoOtro -and $idEmpresaPrueba) {
    $sessionOtro = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/login" -Method Post -Body (@{ email = $empleadoOtro.email; password = $empleadoOtro.password } | ConvertTo-Json) -ContentType "application/json" -SessionVariable sessionOtro | Out-Null

    $rModelos = Invoke-Api GET "/api/v1/modelos" -Session $sessionOtro
    $idModeloPrueba2 = $rModelos.Body.data[0].id

    if ($idModeloPrueba2) {
        # empresa sin vendedor asignado -> como quien crea tiene visibilidad restringida, se autoasigna
        $empresaOtro = @{ ruc = "96" + (Get-Random -Minimum 100000000 -Maximum 999999999); razon_social = "ZZ_QA_EmpresaOtro_$ts"; segmentos = @("urbano") }
        $rEmpresaOtro = Invoke-Api POST "/api/v1/empresas" $empresaOtro -Session $sessionOtro
        $idEmpresaOtro = $rEmpresaOtro.Body.data.id

        $oportunidadOtro = @{ id_empresa = $idEmpresaOtro; id_modelo = $idModeloPrueba2; cantidad = 1; dcto = 10 }
        Check "POST oportunidad con dcto=10 (rol otro, limite 3) -> 422 APROBACION_REQUERIDA" (Invoke-Api POST "/api/v1/oportunidades" $oportunidadOtro -Session $sessionOtro) 422
    } else {
        Write-Host "[SALTADO] No hay modelos." -ForegroundColor Yellow
    }
} else {
    Write-Host "[SALTADO] No se pudo crear el empleado de prueba con rol otro." -ForegroundColor Yellow
}

# ── LIMPIEZA ─────────────────────────────────────────────────────────────
Write-Host "`n=== LIMPIEZA MANUAL PENDIENTE ===" -ForegroundColor Magenta
Write-Host "Busca y borra a mano (Postgres + Drive) todo lo que empiece con ZZ_QA_ o el sufijo $ts :"
Write-Host " - Empleados: $($empleadoPrueba.email), $($empleadoOtro.email)"
Write-Host " - Empresas con RUC iniciando en 99/98/97/96 creadas en esta corrida (revisa por razon_social 'ZZ_QA_%')"
Write-Host " - Sus carpetas correspondientes en Google Drive (busca 'ZZ_QA' en el Drive compartido)"
Write-Host " - Contactos, oportunidades y vinculos asociados a esas empresas (deberian caer con la cascada al borrar la empresa)"
