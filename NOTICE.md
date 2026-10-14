# Avisos de terceiros — Gromatic

O Gromatic é software proprietário (ver `LICENSE`). Ele usa os componentes de terceiros abaixo,
cada um sob a sua própria licença, que continua valendo para esse componente.

## Bibliotecas

| Componente | Titular | Licença |
|---|---|---|
| Kotlin (biblioteca padrão) e kotlinx.coroutines | JetBrains s.r.o. e colaboradores | Apache 2.0 |
| Jetpack Compose, Material 3, AndroidX Activity, Lifecycle e Core | The Android Open Source Project (Google) | Apache 2.0 |
| Google Play Billing Library | Google LLC | Termos do Android Software Development Kit e do Google Play |
| kXML2 (parte do Android; usada nos testes) | Stefan Haustein e colaboradores | MIT |

Texto da licença Apache 2.0: <https://www.apache.org/licenses/LICENSE-2.0>

## Algoritmo adaptado

- **Delaunator** (triangulação de Delaunay por varredura), de Vladimir Agafonkin, licença ISC. O
  arquivo `core/src/main/kotlin/com/appdwg/core/Delaunay.kt` é uma adaptação em Kotlin.

  > ISC License — Copyright (c) 2021, Mapbox. Permission to use, copy, modify, and/or distribute
  > this software for any purpose with or without fee is hereby granted, provided that the above
  > copyright notice and this permission notice appear in all copies. THE SOFTWARE IS PROVIDED
  > "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL
  > IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
  > ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
  > FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
  > TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

## Fontes

Todas sob a **SIL Open Font License 1.1** (texto completo em `docs/licencas/OFL.txt`). As fontes
são distribuídas sem alteração, dentro do app, em `app/src/main/res/font/`.

| Fonte | Titular |
|---|---|
| Barlow | The Barlow Project Authors |
| IBM Plex Sans e IBM Plex Mono | IBM Corp. |
| Manrope | The Manrope Project Authors |
| DM Mono | The DM Mono Project Authors |
| Atkinson Hyperlegible | Braille Institute of America, Inc. |
| JetBrains Mono | The JetBrains Mono Project Authors |

## Mapas de fundo (serviços on-line, opcionais)

- Satélite: Esri, Maxar, Earthstar Geographics e comunidade de usuários GIS.
- Ruas e Topográfico: Esri, HERE, Garmin, USGS, NGA, EPA, NPS, © colaboradores do OpenStreetMap.
- OpenStreetMap: © colaboradores do OpenStreetMap, dados sob a licença ODbL
  (<https://www.openstreetmap.org/copyright>).

Os mapas não são distribuídos com o app: são baixados desses serviços quando o usuário liga o mapa
de fundo, e seguem os termos de uso de cada um.

## Dados baixados da área (Pro)

- **Copernicus DEM GLO-30** (AWS Open Data, `copernicus-dem-30m`): produced using Copernicus WorldDEM-30
  © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the
  European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by
  law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30.
- **OpenStreetMap** via Overpass API: © colaboradores do OpenStreetMap, dados sob a licença ODbL
  (<https://www.openstreetmap.org/copyright>).

## Dados de referência

- Parâmetros de transformação entre SAD69, SAD69(96), Córrego Alegre e SIRGAS 2000: IBGE.
- Definições de sistemas de coordenadas consultadas on-line (opcional): epsg.io, a partir do
  registro EPSG (IOGP).

Marcas citadas (Civil 3D e AutoCAD da Autodesk; Google Play e Google Drive da Google) pertencem aos
seus titulares e são citadas só de forma descritiva.
