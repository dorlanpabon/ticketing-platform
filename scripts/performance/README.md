# Ejecución de rendimiento

La metodología y los límites están en [docs/performance.md](../../docs/performance.md).

```powershell
# No envía tráfico ni crea datos.
python scripts/performance/run.py plan
python scripts/performance/test_run.py
python scripts/performance/run.py validate

# Crea datos y ejecuta carga local. Ejecutar solo fuera de acceptance/expiry/recovery.
python scripts/performance/run.py run --scenario mixed
```

El comando sin argumentos es `plan`. `validate` utiliza `k6 inspect` con red deshabilitada. Solo `run` crea eventos, envía carga y audita resultados.

Imagen oficial fijada: `grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276`. El runner comprueba `k6 v2.4.0` antes de cargar. No usa un tag mutable para ejecutar pruebas.
