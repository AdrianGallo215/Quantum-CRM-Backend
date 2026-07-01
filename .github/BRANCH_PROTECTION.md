# Protección de ramas — quantum-crm-backend

> Parte de B0.4. La protección se configura en GitHub, no vive en el código, por eso
> aquí quedan documentados los ajustes exactos para aplicarlos al conectar el repo.
> Referencia: `docs/DEVOPS-backend.md §2`.

## Estrategia de ramas

```
main          → producción. Siempre deployable. Protegida.
develop       → integración. Protegida.
feature/xxx   → features (desde develop).
fix/xxx       → bugfixes (desde develop; hotfix/ desde main).
```

## Reglas por rama

Ambas ramas (`main`, `develop`):

- ❌ Sin push directo. Solo merge vía Pull Request.
- ✅ Requerir que el check de CI **`Build & Quality Gates`** (job `build` de `ci.yml`) pase antes de mergear.
- ✅ Requerir que la rama esté actualizada con la base antes de mergear.
- ✅ Requerir al menos 1 review aprobado.
- ✅ Requerir resolución de conversaciones antes de mergear.

`main`, adicional:

- ✅ Solo acepta PRs desde `develop` o desde una rama `hotfix/`.
- ✅ Incluir administradores en las restricciones (nadie salta los gates).

## Aplicación (una vez el repo esté en GitHub)

Con GitHub CLI autenticado (`gh auth login`) y el remoto configurado:

```bash
# El nombre del check debe coincidir con el 'name' del job en ci.yml: "Build & Quality Gates"
gh api -X PUT repos/:owner/quantum-crm-backend/branches/main/protection \
  -F required_status_checks.strict=true \
  -F 'required_status_checks.contexts[]=Build & Quality Gates' \
  -F enforce_admins=true \
  -F required_pull_request_reviews.required_approving_review_count=1 \
  -F restrictions=null

gh api -X PUT repos/:owner/quantum-crm-backend/branches/develop/protection \
  -F required_status_checks.strict=true \
  -F 'required_status_checks.contexts[]=Build & Quality Gates' \
  -F enforce_admins=false \
  -F required_pull_request_reviews.required_approving_review_count=1 \
  -F restrictions=null
```

> La regla "main solo acepta PRs desde develop/hotfix" se refuerza con una ruleset o
> con revisión en el PR; GitHub no la impone por API de branch protection clásica.

## Secret requerido en el repo

- **`NVD_API_KEY`** — API key de la NVD (https://nvd.nist.gov/developers/request-an-api-key).
  El workflow la pasa a `dependencyCheckAnalyze`. Sin ella la descarga de la base NVD es
  lentísima. Configurar en *Settings → Secrets and variables → Actions → New repository secret*.

## Verificación de aceptación (B0.4)

La aceptación del plan ("el workflow corre en un PR de prueba y todos los jobs pasan")
se cumple al abrir el primer PR contra `develop` tras conectar el repo a GitHub. En el
runner de GitHub, Docker está disponible, por lo que los tests con Testcontainers corren
sin el bloqueo local de Docker Engine 29.
