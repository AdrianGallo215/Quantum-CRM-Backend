-- =============================================================================
-- V35 — Headless Storage en Google Drive
--
-- El CRM no almacena documentos: solo guarda el ID de la carpeta de Drive
-- donde viven. Cada empresa tiene una carpeta bajo la unidad compartida raiz
-- (ROOT_DRIVE_FOLDER_ID) y cada oportunidad una subcarpeta dentro de la de su
-- empresa.
--
-- NO confundir con `empresas.file_drive`: esa columna es una URL suelta que el
-- usuario pega a mano y se mantiene tal cual. `drive_folder_id` lo administra
-- el backend y nunca se acepta como input del cliente.
--
-- NULLable a proposito: las filas anteriores a esta migracion no tienen
-- carpeta. Se crean bajo demanda en la primera subida (asegurarCarpeta).
-- =============================================================================

ALTER TABLE empresas      ADD COLUMN drive_folder_id VARCHAR(255);
ALTER TABLE oportunidades ADD COLUMN drive_folder_id VARCHAR(255);

COMMENT ON COLUMN empresas.drive_folder_id
    IS 'ID de la carpeta de Google Drive de la empresa, bajo la unidad compartida raiz. Lo administra el backend; SOLO LECTURA para el cliente. Distinto de file_drive (URL manual).';
COMMENT ON COLUMN oportunidades.drive_folder_id
    IS 'ID de la subcarpeta de Google Drive de la oportunidad, hija de empresas.drive_folder_id. Lo administra el backend; SOLO LECTURA para el cliente.';
