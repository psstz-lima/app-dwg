# App DWG

App Android para ver projetos do Civil 3D no campo, sem depender de software da Autodesk no
aparelho. Ele lê **LandXML**, o formato aberto que o Civil 3D (e Bentley, 12d, Carlson, TopoGRAPH)
exporta com os dados reais de projeto.

## O que a versão 0.1 faz

- Abre arquivos LandXML (`.xml`) e DXF ASCII (`.dxf`) do aparelho ou de outros apps (e-mail,
  gerenciador de arquivos).
- DXF: faces 3D (`3DFACE`) e malhas viram superfície com cotas; linhas, polilinhas (inclusive 3D),
  círculos, arcos e splines são desenhados; pontos (`POINT`) aparecem como pontos.
- Mostra a planta: superfície TIN colorida por cota com relevo sombreado, malha de triângulos ao
  aproximar, alinhamentos com estaqueamento (estacas de 20 m) e pontos COGO.
- Ao tocar na planta mostra: alinhamento mais próximo, estaca, afastamento (D/E), cota do terreno,
  cota do greide no eixo e corte ou aterro no eixo.
- Funciona offline.

## Como exportar do Civil 3D

`Saída > Exportar para LandXML`, marque as superfícies e alinhamentos (com os perfis de projeto) e
copie o `.xml` gerado para o celular.

Atenção: o arquivo `*.xml.aecc.pnt` que aparece ao lado é um cache interno do Civil 3D, não o LandXML.

## Como instalar

Compile no PC com o Android Studio (ou `./gradlew :app:assembleDebug`) e instale o APK de
`app/build/outputs/apk/debug/`, ou ligue o celular no USB e use *Run*. O GitHub Actions só confere
que os testes passam e que o app compila.

## Limites conhecidos

- O app não lê DWG. No DXF, objetos do Civil 3D (alinhamentos, superfícies AECC) não são lidos: use
  LandXML para eles, ou explode o desenho antes de salvar o DXF. Blocos, textos, hachuras e imagens do
  DXF ainda não são desenhados (o app avisa). DXF binário não é aceito.
- Corredores chegam no LandXML apenas como superfícies.
- Equações de estaca, elementos `Chain` e `IrregularLine` ainda não são aplicados (o app avisa).
- Curvas verticais assimétricas são tratadas como simétricas.
- Superfícies muito grandes (milhões de pontos) demoram alguns segundos para abrir e usam bastante memória.

## Estrutura

- `core/`: leitor de LandXML e geometria (estaca/afastamento, interpolação no TIN, greide), em Kotlin
  puro e testado na JVM. Rodar testes: `./gradlew -p core test`.
- `app/`: app Android (Jetpack Compose). Gerar APK: `./gradlew :app:assembleDebug` (requer Android SDK).
