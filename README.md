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
- Projeto de plataforma (botão "Projeto"): desenhe o contorno na planta ou use os pontos `_POINTS`;
  cota única com inclinação e direção, ou a cota de cada ponto; taludes de corte e de aterro
  separados (H:V, altura entre bermas, largura e caimento da berma para o pé do talude de cima),
  projetados até encontrar o terreno (offset/daylight). Gera a superfície "Plataforma" (TIN com
  borda, bermas e offset), o mapa de corte/aterro e os volumes contra o terreno; entra na seção, no
  perfil e no comparativo, é salva no projeto e exportada em LandXML (com breaklines e limite) e DXF
  (`_PLAT_BORDA`, `_PLAT_OFFSET`, `_PLAT_BERMA`, em 3D).
- Gerar pontos ("Pontos" > "Gerar pontos…"): sobre uma linha, polilinha 3D ou eixo tocado (distância
  entre pontos, offsets como "-3,5; 0; 3,5", vértices ou pontos notáveis do eixo, faixa de estacas)
  ou em malha (polígono desenhado, polilinha fechada, área visível ou limite da superfície; espaçamento
  X/Y e rotação, que pode vir de uma linha). Cota da linha 3D, da superfície ou fixa, mais altura
  referencial; numeração continua a existente; pré-visualização na planta antes de criar e desfazer.
- Editar linhas (botão "Linhas"): seleção por toque ou janela; mover e copiar (ΔX/ΔY, distância +
  azimute ou ponto a ponto, com ΔZ e cópias múltiplas), offset (vários de uma vez, direita/esquerda/
  ambos, com ΔZ e rampa em % ou H:V para crista/pé de talude), ΔZ, projetar na superfície (drape,
  densificando), achatar numa cota, girar, espelhar, inverter, unir, explodir, dividir (N partes ou a
  cada X m), trocar camada/cor e apagar; propriedades (comprimento 2D/3D, cotas, rampa); pré-
  visualização, desfazer/refazer, salvo no projeto e exportado no DXF. Linhas novas vão para `_EDICAO`.
- Modelos 3D (botão "Modelos 3D"): OBJ (com .mtl), STL, PLY, glTF/GLB e IFC (IFC2x3/IFC4, leitor
  próprio: malhas trianguladas e poligonais, B-rep facetado, sólidos extrudados de perfis retangular,
  circular e poligonal, itens mapeados e posicionamentos encadeados; unidades e cores do arquivo;
  camadas por pavimento e tipo). Posicionado pela georreferência do IFC (IfcMapConversion) ou por ponto
  de inserção (digitado ou tocado, cota do terreno), rotação, escala e unidade. Aparece no 3D e na planta
  (projeção), alinhamento por 2 pontos (coordenadas do modelo → pontos tocados na planta, com escala e
  rotação), mostra nome/tipo/GlobalId ao tocar, liga/desliga por camada, entra no projeto salvo e no
  DXF (3DFACE). FBX/DWG 3D/Revit: exporte em OBJ, glTF ou IFC.
- Estrada/corredor (botão "Estrada"): eixo do arquivo ou novo por PIs (desenhados ou dos `_POINTS`) com
  curvas circulares e espirais (clotoides); greide do arquivo ou por PVIs digitados/gerados no terreno,
  com curvas verticais por comprimento ou K; seção tipo por lado (pista, acostamento, sarjeta, valeta
  em corte), pavimento, taludes de corte e aterro com bermas até o terreno; superelevação por estaca
  (giro no eixo); seções a cada X m e nos pontos notáveis. Gera as superfícies "Corredor" (acabada) e
  "Corredor (terraplenagem)", o eixo com greide (que funciona na seção e no perfil), o mapa de corte/
  aterro, volumes por áreas médias com fator de empolamento, diagrama de Brückner, nota de serviço em
  CSV e as linhas de bordo, acostamento, crista e pé no DXF. Regiões mudam as larguras a partir de uma
  estaca, com transição linear, e o bordo pode seguir uma linha-alvo tocada; a superelevação pode ser
  calculada pelas curvas (velocidade e e máx., critério do DNIT, com giro da pista externa primeiro).
  No eixo de projeto, o perfil longitudinal edita o greide: arraste os PVIs, ajuste ±0,10, adicione ou
  remova e recalcule. O LandXML exportado leva o eixo (retas, curvas e espirais), o greide, as seções
  transversais com áreas e as superfícies com breaklines e limite; a nota de serviço sai também em PDF.
- GPS (botão "GPS"): antena Bluetooth com NMEA (ex.: Hi-Target V200, pareada no Android), GPS do
  aparelho ou arquivo NMEA gravado (simulação). Mostra a posição na planta (cor pelo fix: verde RTK
  fixo, amarelo flutuante), círculo de precisão, cartão com fix, satélites, precisão H/V (GST), E/N/cota
  na zona UTM do projeto, estaca/afastamento no eixo mais próximo e cota da antena menos o terreno;
  opção de seguir a posição. Cliente NTRIP (caster, porta, mountpoint, usuário e senha) repassa as
  correções RTCM à antena e envia a posição (GGA) ao caster a cada 10 s.
- Anotações (botão "Anotar"): toque na planta ou use a posição do GPS; texto, cota e foto da câmera
  ou da galeria (guardada no app). Aparecem como alfinetes; tocar mostra texto, coordenadas e foto.
  Entram no projeto salvo e exportam em KMZ (Google Earth, com as fotos) e CSV.
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
