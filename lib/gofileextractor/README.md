# Gofile Extractor Library

Cliente mínimo para leer enlaces compartidos de Gofile (`/d/<id>`) con una
sesión de invitado y resolver la URL directa de reproducción de sus archivos.

## Cómo funciona

1. **Parseo**: `GofileLinkParser` extrae el id de `gofile.io/d/<id>`.
2. **Sesión**: `POST https://api.gofile.io/accounts` crea una cuenta de invitado y
   devuelve un token. Se crea una vez y se reutiliza.
3. **Listado**: `GET https://api.gofile.io/contents/<id>` (con `Authorization: Bearer <token>`)
   devuelve el contenido. Si es carpeta, trae sus hijos de primer nivel en
   `children` (mapa `id -> entrada`); si es archivo, trae su `link` directo.
4. **Reproducción**: el `link` de cada archivo se descarga con la cookie
   `accountToken=<token>` de la misma sesión, más `Origin` y `Referer` de gofile.io.
   Se vuelve a leer la carpeta contenedora al reproducir para obtener el enlace vigente.

## Header X-Website-Token

Toda petición a la API lleva `X-Website-Token`, un SHA-256 de
`<User-Agent>::en-US::<token de cuenta>::<hora / 14400>::<constante>`.
La constante (`WEBSITE_TOKEN_SALT` en `GofileApi.kt`) sale del frontend web de Gofile y
**puede cambiar**. Si la extensión empieza a fallar con errores de la API, actualiza
ese único valor (se puede obtener revisando el JS de gofile.io o el código de
descargadores comunitarios como `ltsdw/gofile-downloader`).

## Limitaciones conocidas

- Solo se lista el primer nivel de una carpeta; la extensión explora las subcarpetas.
- Los enlaces con contraseña y los no públicos se tratan como no accesibles.
- La paginación de carpetas muy grandes no está verificada.
- El formato exacto de la API se tomó de un cliente comunitario y no se pudo probar
  contra el servicio en vivo al escribir esta lib.
