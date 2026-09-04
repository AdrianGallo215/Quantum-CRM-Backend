# Reglas de negocio — Simulaciones Financieras

> Módulo de simulación del **financiamiento propio de Quantum**. Reemplaza las
> corridas manuales en Excel (`LEASING.xlsx`, `RECONOCIMIENTO_DE_DEUDA.xlsx`).
> Migración: `V43__create_simulaciones.sql` (renumerada desde V40 el 2026-09-03:
> V41 ya se había aplicado a producción y Flyway no admite migraciones fuera de
> orden, ver `docs/planes/plan-04-fundacion-items.md` tarea O6).

---

## 1. Alcance y fronteras

### 1.1 Una simulación = una unidad

Una simulación calcula el financiamiento de **una unidad** (un tipo de bus), no
de la operación completa. Por eso cuelga de `oportunidad_items`, no de
`oportunidades`.

Una oportunidad con 3 modelos distintos lleva 3 simulaciones: no es raro que a
una unidad más grande se le pida una inicial distinta, o que su cuota difiera. Si
la oportunidad tiene un solo ítem, todo se comporta como si colgara de la
oportunidad directamente y **el usuario nunca ve un selector de ítem**.

### 1.2 Frontera con `financiadoras`

Una simulación describe **únicamente la porción financiada por Quantum**.

Lo que el cliente paga a terceros para cubrir su inicial (Calidda, y cajas u
otras financiadoras cuando la unidad es grande y los $45 000 de Calidda no
alcanzan) se resume en **un solo campo** a nivel de ítem:
`oportunidad_items.cuota_financiadora`, editable por el vendedor, por defecto
937.50. El CRM detalla la operación de Quantum, no la de terceros.

- No existe FK entre `simulaciones` y `financiadoras`.
- Una simulación **nunca** lee `financiadoras.*`.
- Que los valores por defecto coincidan con los de Calidda (48 meses, $45 000) es
  coincidencia de negocio, no una relación de datos.

---

## 2. Modos

| | **Leasing** | **Crédito Directo** |
|---|---|---|
| Base del cálculo | Cifras **sin IGV** | Cifras **con IGV** |
| IGV en el cronograma | No se desglosa | Solo sobre el interés |
| Cuota final | `cuota financiera × 1.18` | Promedio de las cuotas con IGV de intereses |

`modo` es **inmutable** tras la creación: las fórmulas y las columnas del
cronograma difieren entre ambos. Cambiar de modo exige "Guardar como Nueva
Simulación". Se hace cumplir en tres niveles: campo deshabilitado en el
frontend, validación en el Service, y trigger `trg_simulacion_modo_inmutable`.

---

## 3. Motor de cálculo

### 3.1 Precisión y redondeo

- Todo el cálculo usa `BigDecimal` con precisión completa. **Nunca `Double`.**
- El redondeo a 2 decimales se aplica **solo al mostrar o guardar**, jamás entre
  meses del cronograma: redondear el saldo de un mes y usarlo como entrada del
  siguiente acumula error y desalinea el balloon.
- La Tasa Nominal Mensual **no se redondea nunca**.
- La función es pura y determinística: mismos campos esenciales ⇒ mismo resultado.

### 3.2 Común a ambos modos

```
PV_efectivo = precio_venta × (1 − descuento/100)
VV          = PV_efectivo / 1.18
IGV         = PV_efectivo − VV
TNM         = (1 + tea/100)^(1/12) − 1
CuotaFin    = PMT(TNM, plazo_meses, −Principal, +valor_residual, 0)
```

`PMT` con convención de Excel: `pv` **negativo**, `fv` **positivo**, vencida
(`type = 0`). Equivalente algebraico:

```
CuotaFin = (Principal × (1+TNM)^n − valor_residual) / (((1+TNM)^n − 1) / TNM)
```

`precio_venta` es **unitario**. La cantidad de unidades del ítem no participa del
cálculo; se usa al agregar a nivel de oportunidad (§6) y en la propuesta PDF.

### 3.3 Leasing

```
CuotaInicial_sinIGV = cuota_inicial / 1.18
Principal           = VV − CuotaInicial_sinIGV
cuota_final         = CuotaFin × 1.18

Mes 0:  SaldoInicial = VV
        Amortización = CuotaInicial_sinIGV
        SaldoFinal   = SaldoInicial − Amortización
        Cuota        = (vacío)

Mes k:  SaldoInicial = SaldoFinal(k−1)
        Interés      = SaldoInicial × TNM
        Amortización = CuotaFin − Interés
        SaldoFinal   = SaldoInicial − Amortización
        Cuota        = CuotaFin
        CuotaConIGV  = CuotaFin × 1.18
```

Columnas del cronograma: `# | Saldo Inicial | Amortización | Interés | Saldo Final | Cuota | Cuota con IGV`.
**Sin columna de IGV** en este modo.

### 3.4 Crédito Directo

```
Principal   = PV_efectivo − cuota_inicial
cuota_final = Σ CuotaConIGVIntereses(1..n) / n

Mes 0:  SaldoInicial = PV_efectivo
        Amortización = cuota_inicial
        SaldoFinal   = SaldoInicial − Amortización
        Cuota        = (vacío)

Mes k:  SaldoInicial          = SaldoFinal(k−1)
        Interés               = SaldoInicial × TNM
        IGV                   = Interés × 0.18
        Amortización          = CuotaFin − Interés
        SaldoFinal            = SaldoInicial − Amortización
        Cuota                 = CuotaFin
        CuotaConIGVIntereses  = CuotaFin + IGV
```

Columnas: `# | Saldo Inicial | Amortización | Interés | IGV | Saldo Final | Cuota | Cuota con IGV de Intereses`.

El divisor del promedio es `plazo_meses` real, **nunca 48 fijo**.

### 3.5 Validación del balloon

El saldo final del último mes debe coincidir con `valor_residual` **por
consecuencia matemática**. Nunca se fuerza ni se ajusta la última amortización.

```
|SaldoFinal(n) − valor_residual| < 0.01   ⇒  válido
```

Con precisión completa el residuo es del orden de 1e−11; la tolerancia existe
solo para absorber ruido de punto flotante. Si se supera, hay un bug en el motor:
debe registrarse en el log de la aplicación y devolverse error, no un cronograma
silenciosamente incorrecto.

El balloon es el saldo final del mes `n`. **No se agrega una fila extra**
(el `LEASING.xlsx` original la tenía como "cuota 49"; era un artificio de la hoja).
Esa última celda va destacada en la UI.

### 3.6 Casos dorados (fixture obligatorio de tests)

Extraídos y verificados contra los Excel de producción, al centavo.

**Leasing** — `PV 110 000 · CI 56 000 · n 48 · TEA 18 · balloon 0`

```
VV         = 93 220.34      Principal = 45 762.71
TNM        = 0.013888430348410033…
CuotaFin   = 1 312.59       cuota_final = 1 548.86

mes  0   SI 93 220.34   Am 47 457.63   SF 45 762.71
mes  1   SI 45 762.71   Int 635.57   Am   677.02   SF 45 085.69   Cuota 1 312.59   c/IGV 1 548.86
mes  2   SI 45 085.69   Int 626.17   Am   686.42   SF 44 399.27   Cuota 1 312.59   c/IGV 1 548.86
mes 48   SI  1 294.61   Int  17.98   Am 1 294.61   SF      0.00   Cuota 1 312.59   c/IGV 1 548.86
```

**Crédito Directo** — `PV 90 000 · CI 45 000 · n 48 · TEA 13 · balloon 35 000`

```
Principal  = 45 000.00
TNM        = 0.010236844358176363…
CuotaFin   = 623.03        cuota_final = 697.67  (promedio)

mes  0   SI 90 000.00   Am 45 000.00   SF 45 000.00
mes  1   SI 45 000.00   Int 460.66   IGV 82.92   Am 162.37   SF 44 837.63   Cuota 623.03   c/IGVInt 705.94
mes  2   SI 44 837.63   Int 459.00   IGV 82.62   Am 164.03   SF 44 673.60   Cuota 623.03   c/IGVInt 705.64
mes 48   SI 35 262.05   Int 360.97   IGV 64.97   Am 262.05   SF 35 000.00   Cuota 623.03   c/IGVInt 688.00
```

---

## 4. Persistencia: qué se guarda y qué no

| Dato | ¿Se guarda? | Por qué |
|---|---|---|
| Campos esenciales | Sí | Estado esencial, entrada del motor |
| Cronograma completo | **No** | Derivable por función pura; guardarlo es complejidad accidental |
| `cuota_final` | Sí | Único derivado persistido: es lo que congela "Guardar Cuota" |
| Nombre autogenerado | **No** | Derivable; solo se guarda si el usuario lo sobrescribe (§8) |
| Diff entre versiones | **No** | Derivable comparando dos snapshots del log (§7.1) |
| PDF de la propuesta | **No** | Se genera y descarga on demand |
| Cálculos de la Calculadora | **No** | Efímeros por diseño (§9) |
| Tipo de cambio | **No** (por simulación) | Global del CRM, siempre en vivo |

`cuota_final` **nunca** se acepta desde el cliente vía API: el backend siempre la
recalcula server-side al guardar. Se persiste solo para no recalcular en listados.

El cronograma se recalcula en cada lectura. Es un bucle de `n` iteraciones sobre
`BigDecimal`: el costo es despreciable frente al riesgo de mantener sincronizadas
48 filas por simulación.

---

## 5. Enlace con ítem y regla de purga

- `id_oportunidad_item` es **opcional**. No hay `id_empresa` ni `id_oportunidad`
  directos: la cadena es `id_oportunidad_item → oportunidad → empresa`. Sin ítem,
  la propuesta omite el nombre de la empresa.
- Al importar desde una oportunidad, si tiene **más de un ítem** el usuario debe
  elegir cuál está simulando. Con un solo ítem se enlaza directo, sin preguntar.
- Al guardar una simulación **sin** ítem, el frontend debe advertir y ofrecer:
  buscar una oportunidad, o confirmar el guardado sin enlace.
- Una simulación que siga con `id_oportunidad_item IS NULL` **30 días** después
  de `created_at` se elimina por **hard delete** vía job programado.
- Aviso al creador **3 días antes** del borrado, vía módulo `notificaciones`.
- Enlazarla a un ítem en cualquier momento la salva de forma definitiva.
- La regla debe ser **visible en la UI**, no solo lógica de servidor: la
  simulación huérfana muestra su fecha de eliminación prevista.
- Al eliminar, se registra el evento `eliminada` con snapshot completo en
  `simulacion_log` (que sobrevive, por eso `id_simulacion` no tiene FK).

---

## 6. Cuota mostrada en la oportunidad

### 6.1 Resolución por ítem

Cada ítem resuelve su cuota de forma independiente:

```
¿El ítem tiene una simulación principal?
├── Sí → usar su cuota_final
└── No → calcular en tiempo real con los parámetros por defecto, sin persistir nada
```

Crear una oportunidad **no** crea simulaciones. La primera cuota estimada de cada
ítem es un cálculo efímero. Solo al editar parámetros y guardar nace una
simulación real.

Parámetros por defecto (constantes de código, no configurables en BD):

| Parámetro | Valor |
|---|---|
| `plazo_meses` | 48 |
| `tea` | 14 |
| `cuota_inicial` | 45 000 |
| `valor_residual` | 25 000 |
| `dias_trabajados` | 22 |
| `comision_estructuracion` | 1 180 |

`precio_venta` y `descuento` se toman del ítem.

### 6.2 Agregación a nivel de oportunidad

Calculado al vuelo en el DTO, **nunca persistido**:

```
Por ítem:
  cuota_quantum_item      = cuota_final de su simulación principal (o cálculo efímero)
  cuota_financiadora_item = oportunidad_items.cuota_financiadora   (editable, default 937.50)
  cuota_total_item        = cuota_quantum_item + cuota_financiadora_item

A nivel oportunidad:
  cuota_quantum_total = Σ (cuota_quantum_item × cantidad_item)
  cuota_total         = Σ (cuota_total_item   × cantidad_item)
  cuota_diaria_total  = cuota_total / dias_trabajados
```

`cuota_total` **solo existe dentro de una oportunidad**: fuera de ella no hay
ítem del cual leer `cuota_financiadora`, así que solo se expone la cuota Quantum
y su diaria.

### 6.3 Simulación principal

- Por defecto es la **última creada** para ese ítem.
- Puede cambiarse manualmente; cada cambio genera un evento `marcada_principal`.
- El índice `uq_simulacion_principal` garantiza una sola por ítem.

---

## 7. Bitácora e historial

`simulacion_log` es la **única** tabla de trazabilidad: permanente, solo INSERT,
sin job de purga. Tipos de evento: `creada`, `editada`, `restaurada`,
`marcada_principal`, `enlazada_a_item`, `eliminada`.

### 7.1 Diff entre versiones

El diff **se computa al leer**, comparando los snapshots de dos eventos
consecutivos. No se persiste: es información derivable de datos que ya existen, y
persistirla abre la posibilidad de que un diff mienta sobre lo que realmente pasó.

### 7.2 Ventana de restauración

El límite de **7 días / 15 versiones** es un filtro de lectura sobre qué puede
restaurarse, **no** una política de borrado. El log completo permanece intacto.

```sql
SELECT * FROM simulacion_log
WHERE id_simulacion = ?
  AND tipo_evento IN ('creada', 'editada', 'restaurada')
  AND created_at > now() - interval '7 days'
ORDER BY created_at DESC
LIMIT 15;
```

Restaurar una versión:
1. Registrar el estado actual como evento `editada` (permite deshacer el deshacer).
2. Copiar los campos esenciales del snapshot a `simulaciones`.
3. **Recalcular** `cuota_final` server-side — nunca copiar la del snapshot, por si
   hubo una corrección de fórmula entre medio.
4. Registrar el evento `restaurada`.

### 7.3 Bifurcación

"Guardar como Nueva Simulación" crea una fila nueva con `id_simulacion_origen`
apuntando a la original, y hereda su enlace a ítem si lo tenía. El origen se
duplica en `simulacion_log.id_simulacion_origen` para que el dato sobreviva
aunque el origen se purgue.

---

## 8. Identificación: nombre y tarjeta

Los usuarios no identifican una simulación por su ID. Cada simulación se presenta
con un **título** legible y sus valores característicos debajo.

### 8.1 Nombre

`simulaciones.nombre` es nullable:

- **`NULL`** → se autogenera al leer, nunca se persiste:
  ```
  {Empresa} · {Modelo} · {Modo} · #{n}
  ```
  Ejemplos:
  `Transportes Lima SAC · MB-O500 · Leasing · #2`
  `Sin enlazar · MB-O500 · Crédito Directo · #1`

- **Con valor** → ese manda. El nombre manual es *pegajoso*: **no se regenera**
  aunque después se editen los parámetros o se enlace a un ítem.

El correlativo `#{n}` cuenta simulaciones dentro del mismo ítem. Para las no
enlazadas, el scope del correlativo es libre (por `modelo + modo`); no es un dato
crítico.

### 8.2 Tarjeta

Sin necesidad de abrir la simulación, la tarjeta muestra:

| Posición | Campo |
|---|---|
| Título | `nombre` (real o autogenerado) |
| Destacado | `cuota_final` |
| Secundarios | `tea` · `valor_residual` · `cuota_inicial` |
| Pie | fecha de última edición |

En el módulo completo, las simulaciones se agrupan por oportunidad o por empresa.

---

## 9. Calculadora Financiera (vendedores)

Módulo aparte, para estimaciones rápidas durante la prospección.

- Usa **exactamente el mismo motor** de cálculo: ambos modos, cronograma
  completo, misma `<PropuestaFinanciera/>` y exportación PDF.
- Puede jalar **opcionalmente** una empresa o un modelo, aunque todavía no exista
  una oportunidad.
- **Cero persistencia**: no escribe en `simulaciones` ni en `simulacion_log`. No
  deja rastro de auditoría.
- Un botón **"Enlazar a Oportunidad"** convierte el cálculo efímero en una
  simulación real: recién ahí se crea la fila y su evento `creada`. El vendedor
  solo puede enlazar a ítems de oportunidades donde él es el vendedor asignado.
- Antes de enlazar no existe ítem, así que **no puede mostrar `cuota_total`**:
  solo la cuota Quantum.

Implicación de arquitectura: el motor de cálculo debe vivir **aislado** del
Service de `simulaciones`, porque lo consumen dos flujos — uno que persiste y uno
que no.

---

## 10. Permisos

| Rol | Módulo Simulaciones | Simulador en su oportunidad | Calculadora Financiera |
|---|---|---|---|
| `admin` | Total | Sí | Sí |
| `analista` | Total | Sí | Sí |
| `gerencia` | Total | Sí | Sí |
| `vendedor` | **Sin acceso** | Solo donde es el vendedor asignado | Sí |
| `jdv`, `otro` | Sin acceso | No | No |

`analista` es de solo lectura en oportunidades pero tiene **escritura completa**
en simulaciones: es el rol dueño de este módulo.

Este reparto es candidato a cambiar: debe estar centralizado en un solo punto de
decisión, no disperso en condicionales por endpoint.

---

## 11. Propuesta y exportaciones

- **Propuesta**: componente `<PropuestaFinanciera/>` que renderiza en HTML desde
  los datos de la simulación. Es la previsualización, siempre disponible.
- **PDF**: se genera y descarga on demand desde esa misma propuesta. **No se
  almacena** ni se registra como archivo.
- **Excel**: exportación del cronograma con el formato de las hojas actuales.
- La propuesta muestra la cantidad de unidades del ítem y el modelo
  (`id_modelo`), que no participan del cálculo.

---

## 12. Tipo de cambio

Variable **global del CRM**, no de la simulación:

- Job programado diario que consulta SUNAT y actualiza el valor almacenado.
- Si SUNAT no responde, se conserva el último valor guardado, sin error visible.
- Se usa **siempre en vivo**, nunca como snapshot por simulación.
- Se muestra de forma permanente y discreta en una esquina del layout del CRM
  (requisito del layout global, no de este módulo).

---

## 13. Validaciones de negocio (Service, no BD)

- `cuota_inicial < PV_efectivo`, donde `PV_efectivo = precio_venta × (1 − descuento/100)`.
  Vive en el Service por requerir la fórmula del descuento.
- `valor_residual < Principal`.
- `modo` inmutable en `UPDATE`.
- `cuota_final` recalculada server-side, nunca aceptada del cliente.
- `|SaldoFinal(n) − valor_residual| < 0.01`, o error.
- Al enlazar desde la Calculadora: el ítem debe pertenecer a una oportunidad del
  propio vendedor.

Los CHECK de rango (positivos, 0-100, TEA < 200) están en BD; ver migración.
