# Modelo de ramas

Git Flow, adaptado a un equipo pequeño. La regla de fondo: **`main` es lo que
está en producción** y solo recibe código que ha pasado por una rama de release
o de hotfix.

```
main      ●──────────────────────●───────────────●────────▶  producción (v1.0.0, v1.0.1, v1.1.0…)
           \                    / \             / \
hotfix/*    \                  /   ●──hotfix──●   \
             \                /     \              \
release/*     \      ●──1.0.0──●     \       ●──1.1.0──●
               \    /           \     \     /           \
develop         ●──●────●────●───●─────●───●─────────────●──▶  integración
                    \  /      \ /
feature/*            ●●        ●●
```

## Ramas permanentes

| Rama | Para qué | Quién escribe en ella |
|---|---|---|
| `main` | Producción. Cada commit de `main` es una versión publicada y lleva su etiqueta `vX.Y.Z`. | Solo merges de `release/*` y `hotfix/*`. Nunca commits directos. |
| `develop` | Integración: lo terminado que irá en la próxima versión. Su versión es la siguiente con `-SNAPSHOT`. | Merges de `feature/*`, y de vuelta los de `release/*` y `hotfix/*`. |

## Ramas temporales

| Rama | Sale de | Vuelve a | Para qué |
|---|---|---|---|
| `feature/<nnn-nombre>` | `develop` | `develop` | Una feature de Spec Kit (`feature/009-cuadrantes`) o un cambio de infraestructura (`feature/actualizar-kotlin`). |
| `release/X.Y.Z` | `develop` | `main` **y** `develop` | Estabilizar una versión: fijar la versión, corregir fallos, preparar el despliegue. **Nada de features nuevas.** |
| `hotfix/X.Y.Z` | `main` | `main` **y** `develop` | Corregir un fallo en producción sin arrastrar lo que haya en `develop`. |

Las ramas `*-feature` anteriores a este modelo (`auth-feature`,
`export-feature`, `invoices-feature`, `time-track-feature`, `backlog-feature`)
ya están integradas en `main` y se pueden borrar.

## Versiones

[SemVer](https://semver.org/lang/es/): `MAJOR.MINOR.PATCH`.

- **MINOR** (`1.1.0`): una o más features nuevas. Sale de `develop` por una release.
- **PATCH** (`1.0.1`): solo correcciones. Sale de un hotfix (o de una release que solo corrige).
- **MAJOR** (`2.0.0`): un cambio incompatible en la API (`docs/openapi.json`) que obligue a las apps a cambiar.

La versión vive en un solo sitio, el `build.gradle.kts` de la raíz (todos los
módulos la heredan, `/actuator/info` la muestra y la publicación de la imagen
comprueba que la etiqueta coincide):

- en `develop`: la siguiente con `-SNAPSHOT` (`1.1.0-SNAPSHOT`);
- en `release/X.Y.Z` y `hotfix/X.Y.Z`: `X.Y.Z`, sin `-SNAPSHOT`.

## Pasos

### Una feature

```bash
git checkout develop && git pull
git checkout -b feature/009-cuadrantes
# … /speckit-specify, plan, tasks, implement; commits por fases …
git push -u origin feature/009-cuadrantes     # y PR contra develop
```

### Una release

```bash
git checkout develop && git pull
git checkout -b release/1.1.0
# versión 1.1.0 en build.gradle.kts, CHANGELOG.md, solo correcciones
git push -u origin release/1.1.0              # PR contra main
# tras el merge en main:
git checkout main && git pull
git tag -a v1.1.0 -m "Granatum Suite 1.1.0" && git push origin v1.1.0
# la etiqueta publica la imagen ghcr.io/<owner>/<repo>:1.1.0 (publicar-imagen.yml)
git checkout develop && git merge --no-ff main # trae las correcciones de la release
# y en develop la versión pasa a 1.2.0-SNAPSHOT
```

### Un hotfix

```bash
git checkout main && git pull
git checkout -b hotfix/1.0.1
# versión 1.0.1, la corrección con su test, CHANGELOG.md
git push -u origin hotfix/1.0.1               # PR contra main
# tras el merge: etiqueta v1.0.1 en main y merge de main en develop, como arriba
```

## Lo que lo hace cumplir

- **CI** (`.github/workflows/ci.yml`) corre en todas las ramas y PR, y el
  trabajo `politica-ramas` **falla cualquier PR contra `main` que no venga de
  `release/*` o `hotfix/*`**.
- **En GitHub** (Settings → Branches), a configurar a mano en `main` y `develop`:
  - exigir PR antes de fusionar y que pasen `build`, `imagen` y `politica-ramas`;
  - impedir pushes directos y force-push;
  - en `main`, además, exigir la rama actualizada antes de fusionar.
