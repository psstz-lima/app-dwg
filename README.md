# App DWG

App Android para ver projetos do Civil 3D no campo, sem depender de software da Autodesk no
aparelho. Ele lê **LandXML**, o formato aberto que o Civil 3D (e Bentley, 12d, Carlson, TopoGRAPH)
exporta com os dados reais de projeto.

## O que a versão 0.1 faz

- Abre arquivos LandXML (`.xml`) do aparelho ou de outros apps (e-mail, gerenciador de arquivos).
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

Cada push gera um APK em **Actions > Build > (execução) > Artifacts > app-dwg-debug-apk**.
Baixe, descompacte e instale no Android (é preciso permitir apps de fontes desconhecidas).

## Limites conhecidos

- O app não lê DWG nem DXF: os objetos do Civil 3D só podem ser lidos fora dele com bibliotecas pagas.
- Corredores chegam no LandXML apenas como superfícies.
- Equações de estaca, elementos `Chain` e `IrregularLine` ainda não são aplicados (o app avisa).
- Curvas verticais assimétricas são tratadas como simétricas.
- Superfícies muito grandes (milhões de pontos) demoram alguns segundos para abrir e usam bastante memória.

## Estrutura

- `core/`: leitor de LandXML e geometria (estaca/afastamento, interpolação no TIN, greide), em Kotlin
  puro e testado na JVM. Rodar testes: `./gradlew -p core test`.
- `app/`: app Android (Jetpack Compose). Gerar APK: `./gradlew :app:assembleDebug` (requer Android SDK).
