# App DWG

App Android para ver projetos do Civil 3D no campo, sem depender de software da Autodesk no
aparelho. Ele lê **LandXML**, o formato aberto que o Civil 3D (e Bentley, 12d, Carlson, TopoGRAPH)
exporta com os dados reais de projeto.

## O que a versão 0.1 faz

- Abre arquivos LandXML (`.xml`), DXF ASCII (`.dxf`) e caches de pontos do Civil 3D
  (`*.xml.aecc.pnt`, triangulados em superfície) do aparelho ou de outros apps (e-mail,
  gerenciador de arquivos).
- DXF: faces 3D (`3DFACE`) e malhas viram superfície com cotas; linhas, polilinhas (inclusive 3D),
  círculos, arcos, elipses, splines, textos (`TEXT`/`MTEXT`), hachuras (preenchimento translúcido)
  e blocos (`INSERT`) são desenhados nas cores do CAD.
- Imagens do DXF (ortofotos): escolha o arquivo em "Imagens"; lê TIFF/GeoTIFF (inclusive BigTIFF, LZW,
  Deflate, JPEG, tiles e overviews), JPG, PNG e WebP. ECW não (formato fechado): converta para TIFF.
- Referências externas (botão "Referências"), como a paleta XREF do AutoCAD: imagens e desenhos
  anexados (XREF em DXF), com escolher arquivo, descarregar/recarregar, desanexar e edição do caminho
  salvo; "Salvar cópia do DXF" grava os caminhos novos. Camadas de XREF aparecem como "XREF|camada".
- Vista 3D (botão "3D") com OpenGL ES: terreno sombreado e linhas 3D do DXF. Um dedo gira (orbit),
  dois dedos movem (pan), pinça aproxima; "Z ×1/×2/×3" exagera a altura.
- Painel de camadas: ligar/desligar, congelar (também tira a camada da consulta de cotas), ver e
  trocar a cor. Começa com o estado das camadas salvo no desenho.
- Mostra a planta: superfície TIN colorida por cota com relevo sombreado, malha de triângulos ao
  aproximar, alinhamentos com estaqueamento (estacas de 20 m) e pontos COGO.
- Ao tocar na planta mostra: alinhamento mais próximo, estaca, afastamento (D/E), cota do terreno,
  cota do greide no eixo e corte ou aterro no eixo.
- Medir (botão "Medir"): toque pontos na planta para ver distância, azimute, desnível e inclinação de
  cada trecho, comprimento total (horizontal e inclinado) e, a partir de 3 pontos, área e perímetro.
  O toque encaixa em pontos COGO e vértices de linhas e alinhamentos próximos.
- Seção transversal (botão "Seção"): escolha a estaca digitando ("12+5,40"), com −20/+20 ou tocando
  na planta; mostra todas as superfícies na largura escolhida (±10 a ±100 m), o greide no eixo e o
  corte ou aterro. Arraste no gráfico para ler as cotas em qualquer afastamento.
- Comparar (botão "Comparar"): adicione outros arquivos (DXF, LandXML, .aecc.pnt) ao projeto aberto,
  todos sobrepostos, com as superfícies nomeadas pelo arquivo. Escolha a superfície A (base) e a B e
  veja o mapa colorido de B − A com legenda, a diferença no ponto tocado e os volumes de corte e
  aterro na área comum (malha de até 2 milhões de células). A seção mostra todas as superfícies.
- Pontos (botão "Pontos"), camada `_POINTS`: importa TXT/CSV do Civil 3D no formato PDENZ (número,
  descrição, E, N, cota; separador vírgula, ponto e vírgula, tab ou espaço), cria ponto tocando na
  planta (número automático, cota da superfície), edita, apaga e exporta em PDENZ. Tocar no ponto
  mostra os dados com 3 casas; medir encaixa nos pontos; "Gerar superfície" triangula os pontos.
- Comparação cruzada e por etapas ("Comparar" > "Comparação cruzada e por etapas…"): marque várias
  superfícies na ordem (terreno original, levantamentos, projeto). A matriz mostra corte, aterro e
  saldo de cada par (linha − coluna) e um toque na célula leva o mapa para a planta; as etapas
  mostram o volume de cada período, o executado, o que falta até o projeto e o % concluído. O cartão
  do ponto lista a cota de cada superfície marcada.
- Salvar projeto ("Salvar" / "Salvar como"): grava um arquivo `.appdwg` com o desenho aberto, os
  arquivos adicionados, a imagem sobre a superfície, os pontos `_POINTS`, camadas e cores, medição,
  seção, comparação e referências. "Abrir" num `.appdwg` restaura tudo. Os arquivos não são
  copiados: o projeto guarda onde estão. Ao abrir outro arquivo ou sair com alterações não salvas,
  o app pergunta se quer salvar.
- Exportar (botão "Exportar"): DXF do desenho (linhas, textos, eixos, pontos `_POINTS`, medição e
  linha da seção; superfícies como 3DFACE se marcado), LandXML das superfícies (inclusive as geradas
  do `.aecc.pnt` ou dos pontos) e pontos, pontos em PDENZ, KML para o Google Earth (UTM SIRGAS 2000
  convertido para latitude/longitude), CSV dos volumes (comparação, matriz e etapas) e a tela em
  PNG ou PDF.
- Perfil longitudinal (botão "Perfil"): terreno de cada superfície e greide ao longo do eixo, PVIs
  com curvas verticais e rampas em %, janela inteira ou ±500/±200/±50 m. Arraste no gráfico ou toque
  na planta para ler estaca, cotas e corte/aterro; a posição aparece na planta.
- Ir para (botão "Ir para"): busca ponto, texto ou alinhamento pelo nome (sem diferenciar acentos),
  vai a uma estaca e afastamento de um alinhamento ou a uma coordenada E/N; a planta centraliza e
  mostra os dados do lugar.
- Curvas de nível (botão "Curvas"): intervalo automático pelo zoom ou fixo (0,5 a 10 m), mestras a
  cada 5 com cota, calculadas para a área visível. O DXF exportado pode levá-las (camada `_CURVAS`,
  com cota).
- Funciona offline.

## Como exportar do Civil 3D

`Saída > Exportar para LandXML`, marque as superfícies e alinhamentos (com os perfis de projeto) e
copie o `.xml` gerado para o celular.

Atenção: o arquivo `*.xml.aecc.pnt` que aparece ao lado é um cache interno do Civil 3D, não o LandXML.
O app abre esse cache como superfície (Delaunay dos pontos), mas sem linhas de quebra nem contornos.

## Como instalar

Compile no PC com o Android Studio (ou `./gradlew :app:assembleDebug`) e instale o APK de
`app/build/outputs/apk/debug/`, ou ligue o celular no USB e use *Run*. O GitHub Actions só confere
que os testes passam e que o app compila.

## Limites conhecidos

- O app não lê DWG. No DXF, objetos do Civil 3D (alinhamentos, superfícies AECC) não são lidos: use
  LandXML para eles, ou explode o desenho antes de salvar o DXF. Imagens e cotas (`DIMENSION`) do DXF
  ainda não são desenhadas (o app avisa); hachuras aparecem como preenchimento, sem o padrão. DXF
  binário não é aceito.
- Corredores chegam no LandXML apenas como superfícies.
- Equações de estaca, elementos `Chain` e `IrregularLine` ainda não são aplicados (o app avisa).
- Curvas verticais assimétricas são tratadas como simétricas.
- Superfícies muito grandes (milhões de pontos) demoram alguns segundos para abrir e usam bastante memória.

## Estrutura

- `core/`: leitor de LandXML e geometria (estaca/afastamento, interpolação no TIN, greide), em Kotlin
  puro e testado na JVM. Rodar testes: `./gradlew -p core test`.
- `app/`: app Android (Jetpack Compose). Gerar APK: `./gradlew :app:assembleDebug` (requer Android SDK).
