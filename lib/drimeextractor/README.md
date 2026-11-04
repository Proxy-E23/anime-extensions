# Drime Extractor Library

Cliente mínimo para leer enlaces compartidos de Drime Cloud (`/drive/s/<hash>`)
sin iniciar sesión y resolver la URL directa de reproducción de sus archivos.

## Cómo funciona

1. **Parseo**: `DrimeLinkParser` extrae el hash de `app.drime.cloud/drive/s/<hash>`,
   `.../drive/shares/<hash>` o `dri.me/<hash>`.
2. **Listado**: `GET /api/v1/shareable-links/<hash>?withEntries=true` devuelve el
   enlace (`link`, con su `id` numérico y la entrada raíz) y, si es carpeta, sus
   hijos de primer nivel en `folderChildren` (paginados, 50 por página).
3. **Reproducción**: `GET /api/v1/file-entries/download/<hash del archivo>?shareable_link=<id numérico>`
   responde con un 302 a una URL firmada de almacenamiento (24 h, acepta Range).
   Se lee el header `Location` sin seguir la redirección.

## Limitaciones conocidas

- Solo se lista el primer nivel de una carpeta (las subcarpetas no se exploran).
- Los enlaces con contraseña se tratan como no accesibles.
- La paginación más allá de la primera página (`&page=N`) no está verificada.
