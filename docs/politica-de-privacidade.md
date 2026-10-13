# Política de privacidade — Gromatic

*Rascunho — revisar antes de publicar. Última atualização: 13 de outubro de 2026.*

O Gromatic ("o app") é um aplicativo Android para visualizar e trabalhar em campo com dados de topografia e terraplenagem (LandXML, DXF, pontos COGO). Esta política explica quais dados o app usa e o que faz com eles.

## Resumo

- O app **não tem conta de usuário, anúncios, nem análise de uso**.
- A assinatura **Gromatic Pro** é comprada pelo **Google Play**. O desenvolvedor não recebe nem vê seus dados de pagamento.
- O app **não envia seus arquivos, pontos, anotações ou fotos** para servidores do desenvolvedor. Esses dados ficam no seu aparelho, ou nos lugares onde você mesmo os salva ou compartilha.
- A localização é usada **só enquanto o GPS está ligado no app**, para mostrar sua posição na planta. Ela não é guardada pelo app nem enviada ao desenvolvedor.

## Dados tratados e para quê

| Dado | Uso | Onde fica |
|---|---|---|
| Arquivos que você abre (LandXML, DXF, pontos, imagens, modelos 3D) | Mostrar e calcular no app | No aparelho. O app só lê os arquivos que você escolhe |
| Projetos, pontos, linhas e anotações que você cria | Salvar e reabrir o trabalho | Nos arquivos que você salva e no armazenamento interno do app |
| Fotos das anotações | Anexar à anotação | No armazenamento interno do app. Saem do aparelho só quando você exporta ou compartilha |
| Localização (GPS do aparelho ou antena GNSS por Bluetooth) | Mostrar a posição na planta enquanto o GPS está ligado | Só na memória, durante o uso |
| Posição aproximada enviada ao caster NTRIP | Exigida por algumas redes de correção (mensagem GGA) | Vai só para o caster que **você** configura, e só quando você conecta o NTRIP |
| Usuário e senha NTRIP | Conectar ao caster | No aparelho, nas preferências do app |

## Serviços de terceiros

- **Mapas de fundo** (opcional, desligado por padrão): para mostrar o mapa de fundo, o app baixa imagens ("tiles") dos servidores da **Esri** (ArcGIS Online) ou do **OpenStreetMap**. Como em qualquer acesso à internet, esses servidores recebem o endereço IP do aparelho e quais tiles foram pedidos, o que indica a região vista. Valem as políticas de privacidade desses serviços. Os tiles baixados ficam salvos no aparelho para uso offline, e você pode apagá-los em *Mapa › Apagar mapas salvos*.
- **Google Play (assinatura Gromatic Pro)**: a versão grátis abre e mostra a planta; as demais ferramentas fazem parte da assinatura Gromatic Pro (mensal ou anual). A compra, o pagamento, a renovação e o cancelamento são feitos pelo Google Play, com a sua conta Google, e seguem a política de privacidade do Google. O app consulta o Google Play só para saber se há uma assinatura ativa, e guarda no aparelho apenas essa informação e a data da última consulta (para funcionar sem internet por até 7 dias). Nenhum dado de pagamento passa pelo app ou pelo desenvolvedor.
- **Google Drive** (opcional): quando você abre ou salva um arquivo no Google Drive, o app usa o seletor de arquivos do Android. O arquivo vai só para a sua conta, e o app acessa apenas os arquivos que você escolhe.
- **Caster NTRIP** (opcional): se você configurar correções RTK, o app se conecta ao endereço que você informar, com o usuário e a senha que você informar.

O app não usa SDKs de publicidade, de análise ou de relatórios de falha de terceiros.

## Permissões do Android

- **Localização**: pedida só quando você liga o GPS do aparelho no app.
- **Dispositivos próximos / Bluetooth**: pedida só quando você conecta uma antena GNSS por Bluetooth.
- **Câmera**: o app não pede essa permissão. As fotos das anotações são tiradas pelo app de câmera do sistema.
- **Internet**: para mapas de fundo, NTRIP e consulta de sistemas de coordenadas, quando você usa esses recursos.
- **Faturamento do Google Play** (`com.android.vending.BILLING`): para comprar e verificar a assinatura Gromatic Pro.

## Compartilhamento

Os dados só saem do aparelho quando **você** exporta ou compartilha um arquivo (por exemplo DXF, LandXML, KML/KMZ, CSV ou PDF), para o destino que você escolher.

## Retenção e exclusão

Os dados ficam no aparelho até você apagá-los. Para apagar tudo, desinstale o app ou use *Configurações do Android › Apps › Gromatic › Armazenamento › Limpar dados*. Os arquivos que você salvou fora do app (por exemplo em Downloads) continuam até você apagá-los.

## Crianças

O app é uma ferramenta profissional e não se destina a crianças.

## Contato

Dúvidas sobre esta política: **[e-mail de contato do desenvolvedor — a definir]**.

## Alterações

Mudanças nesta política serão publicadas nesta página, com a data de atualização.
