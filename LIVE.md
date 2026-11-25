# XStreaming Live: transmitir o Xbox ao vivo no YouTube

Esta versão do XStreaming tem um botão **"Iniciar live"**. Ele manda a imagem e o som do seu
Xbox Series S direto para o YouTube, junto com o seu microfone (opcional), sem gravar a tela.

## Como funciona (sem jargão)

- O Xbox manda o jogo para o celular pela internet (Remote Play). O XStreaming já recebe isso.
- A imagem já chega pronta no formato que o YouTube aceita, então o app só **repassa**.
  Por isso o celular quase não faz esforço extra: menos bateria e menos calor.
- O som do jogo é pego no momento em que ia sair no alto-falante. Se você ligar o microfone,
  a sua voz é misturada a ele. Tudo vai junto para o YouTube.

## Primeiro uso

1. No computador ou no celular, abra o **YouTube Studio** > **Criar** > **Transmitir ao vivo**.
   Na primeira vez o YouTube pode pedir para ativar a transmissão ao vivo (leva até 24 h).
2. Na aba **Stream**, copie a **Chave de transmissão**.
3. No app **XStreaming Live**: **Configurações** > **Live no YouTube** > cole a chave > **Salvar**.
4. Ligue o microfone se quiser falar na live (use fone de ouvido).
5. Entre no seu console e comece a jogar.
6. Abra o menu do jogo (o mesmo onde tem "Iniciar gravação") e toque em **🔴 Iniciar live**.
7. Aparece "CONECTANDO…" e depois **"AO VIVO"** no canto da tela. Em uns 10 a 20 segundos a
   imagem aparece no YouTube Studio.
8. Para terminar: menu do jogo > **🔴 Encerrar live**. Sair do jogo também encerra a live.

## Qualidade

A live usa a imagem do jeito que o Xbox manda. Para mudar a qualidade, use as configurações
normais do XStreaming (resolução 720p/1080p e taxa de bits do console).
Dica: comece com **720p**. Se o YouTube mostrar avisos de "conexão ruim", baixe a taxa de bits.

O celular **recebe e envia** o vídeo ao mesmo tempo, então precisa de uma internet com bom
**upload** (envio): de preferência 10 Mbps ou mais, no Wi-Fi.

## Recomendações

- Use o modo de renderização **Native** (é o padrão). No modo **Nano** a live também funciona,
  mas o YouTube pode reclamar da "frequência de quadros-chave".
- Use **fone de ouvido** se o microfone estiver ligado. Sem fone, o microfone pega o som do
  jogo do alto-falante e quem assiste ouve o jogo duas vezes.
- Deixe o celular carregando em lives longas.

## Suposições desta primeira versão (a confirmar no teste)

1. O vídeo do Xbox chega em H.264 (é o único formato que o XStreaming oferece hoje).
2. A resolução não muda no meio da live. Se mudar, a imagem no YouTube pode ficar com defeitos
   até reiniciar a live.
3. O microfone do celular pode ser usado ao mesmo tempo que o XStreaming. Se a opção do
   XStreaming de "microfone para o Xbox" (chat) estiver ligada, alguns celulares podem
   não deixar os dois usarem o microfone juntos.
4. A versão 2.8.2 do RootEncoder funciona com as ferramentas antigas que o XStreaming usa.
5. Se a internet cair, o app tenta reconectar sozinho até 10 vezes (a cada 5 s).

## Termos e licenças

- **XStreaming** (github.com/Geocld/XStreaming) é licenciado sob **AGPL-3.0**.
  Este app é uma modificação dele, então também é AGPL-3.0: se você distribuir o APK para
  outras pessoas, precisa disponibilizar o código-fonte. Para uso pessoal, nada muda.
- **RootEncoder** (github.com/pedroSG94/RootEncoder) é **Apache-2.0**, compatível com a AGPL.
- O XStreaming **não é oficial** da Microsoft. Uma mudança da Microsoft pode fazê-lo parar até
  ser atualizado.
- As regras da Microsoft para conteúdo de jogos permitem transmitir gameplay. Alguns jogos
  têm regras próprias (por exemplo, partes com spoiler). O YouTube pode silenciar trechos com
  música protegida por direitos autorais.

## Para quem for mexer no código

- `android/app/src/main/java/com/xstreaming/live/LiveStreamer.java`: envia para o RTMP.
  Recebe os quadros H.264 de `LowLatencyVideoDecoder` (motor Native) e `NanoRustBridge`
  (motor Nano), e o som PCM de `DelegatingAudioTrack` / `NanoRustBridge`, nos mesmos pontos
  que o gravador de partidas (`GameRecorder`) já usava.
- `src/utils/useLiveStream.ts`: botão, estado e o pedido de quadro-chave a cada 2 s.
- `src/pages/LiveSettings.tsx` e `src/store/liveStore.ts`: tela e armazenamento da chave
  (guardada separada das outras configurações, para não ir para logs nem para a exportação).
- Build: sabor `live` (`./gradlew assembleLiveRelease`), id `com.xstreaming.live`, instala ao
  lado do XStreaming oficial. O workflow `.github/workflows/build-live-apk.yml` gera o APK.
