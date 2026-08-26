# RawPreviewCam

App mínimo Android (Kotlin, Camera2 API) que:

1. Abre a câmera traseira e mostra o preview em tela cheia.
2. Ao apertar o botão, salva um **JPEG** gerado a partir do frame de
   **preview** atual (não de um still capture), evitando o pipeline de
   processamento pesado que os fabricantes aplicam nas fotos "normais".
3. As fotos são salvas em armazenamento privado do app:
   `Android/data/com.example.rawpreviewcam/files/IMG_YYYYMMDD_HHmmss.jpg`
   (visível via Android Studio Device Explorer, ou copie pra Fotos com um
   file manager / `adb pull`).

## Como rodar

1. Abra a pasta `RawPreviewCam/` no **Android Studio** (versão recente,
   Hedgehog/Iguana ou mais nova).
2. Deixe o Android Studio baixar o Gradle wrapper e sincronizar
   (`File > Sync Project with Gradle Files` se não abrir automático).
3. Conecte um celular Android físico (a câmera não funciona bem em
   emulador) com depuração USB ativada.
4. Rode (▶) — o app vai pedir permissão de câmera na primeira abertura.

## Ajustes que você provavelmente vai querer fazer

- **Resolução do preview**: `chooseBestPreviewSize()` limita a 3840x2160.
  Ajuste esse teto se seu device suportar preview em resolução maior
  (ou menor, se quiser mais performance).
- **Qualidade do JPEG**: `onCaptureClicked()` chama
  `yuv420888ToJpegBytes(img, quality = 100)` — pode baixar pra reduzir
  tamanho de arquivo.
- **Salvar na galeria pública**: hoje salva em armazenamento privado do
  app (não aparece no app de Fotos). Se quiser que apareça na galeria,
  precisa usar `MediaStore` (Android 10+) — posso adicionar isso se
  quiser.
- **NOISE_REDUCTION_MODE_MINIMAL / EDGE_MODE_OFF**: já estão setados na
  request de preview pra deixar o frame o mais "cru" possível. Em alguns
  devices o hardware não suporta essas flags no preview e a Camera2 API
  ignora silenciosamente — não é bug, é limitação do HAL do fabricante.
- **Layout YUV não-NV21**: a conversão assume `pixelStride`/`rowStride`
  padrão (comum na maioria dos devices). Em aparelhos com layout exótico,
  a imagem pode sair com cores erradas — nesse caso me avisa que eu
  ajusto a conversão pra ler `pixelStride`/`rowStride` manualmente.

## Observação importante

Eu não tenho como compilar/testar isso num Android SDK real aqui — revisei
o código com cuidado mas é possível que precise de pequenos ajustes ao
rodar no Android Studio (erros de sintaxe, versão de Gradle/AGP no seu
ambiente, etc). Se dar erro na hora de compilar, me manda a mensagem de
erro que eu corrijo.
