# Áudio Share

Aplicativo Android de intercomunicação por voz em uma rede Wi‑Fi local. Um aparelho inicia como **servidor** e os demais entram como **participantes**. O servidor retransmite o áudio dos participantes, e cada aparelho também envia o próprio microfone. O servidor pode ainda reproduzir uma pasta de músicas e transmitir a música aos participantes.

> Projeto experimental. A versão `1.0.1` reúne melhorias no buffer adaptativo, no reprodutor de músicas e no layout responsivo. É distribuída para instalação direta (sideload) e ainda não está preparada para publicação na Play Store.

## O que o app faz

- Transmite a voz de cada aparelho para os outros aparelhos conectados.
- Permite vários participantes na mesma sessão, usando o servidor como ponto de retransmissão.
- Encontra o servidor automaticamente na rede local; não é necessário informar endereço IP.
- Funciona em uma rede Wi‑Fi comum ou em um hotspot, desde que todos os aparelhos estejam na mesma rede e ela permita comunicação entre clientes.
- Mostra os participantes conectados no servidor e métricas de RTT, perda estimada e buffer adaptativo nos clientes.
- Mantém o serviço de áudio em primeiro plano enquanto a sessão está ativa.
- No servidor, permite escolher uma pasta de músicas pelas configurações, selecionar faixas numa lista e reproduzir, pausar, avançar, voltar e ativar reprodução aleatória.
- Oferece controles de mídia do Android, incluindo os controles compatíveis de fones e relógios.
- Ajusta separadamente o ganho da música e o nível da voz enviada por cada aparelho, com medição do nível captado pelo microfone.
- Ajusta a área da lista de músicas ao espaço disponível entre os controles superiores e o player fixo no rodapé.

## Controles de áudio

### Música do servidor

O slider vai de `0%` a `100%`, mas controla uma faixa de ganho de áudio de `0%` a `25%`. Por exemplo, `20%` no slider aplica `5%` de ganho e `50%` aplica `12,5%`. O padrão é `10%` no slider (`2,5%` de ganho). O volume físico de cada telefone ou fone continua sendo controlado pelo próprio Android.

A música só é reproduzida pelo servidor. Os participantes recebem a música junto com a conversa.

### Voz enviada

Cada aparelho tem seu próprio controle de envio da voz, entre `0%` e `200%`. O servidor também usa o seu para o microfone dele. O ajuste só afeta a voz capturada naquele aparelho; não muda a voz de outros participantes nem o volume recebido.

O controle é ganho digital aplicado ao áudio do microfone. Acima de `100%` pode amplificar ruído e causar distorção; não altera a sensibilidade física do microfone.

## Reprodutor de músicas

1. Inicie o aparelho como servidor.
2. Abra **Configurações** (engrenagem), escolha **Selecionar pasta de músicas** e conceda acesso à pasta.
3. Toque numa faixa da lista para selecioná-la; use os controles fixos do player para reproduzir, pausar e navegar.
4. O player fica no rodapé; a lista ocupa o espaço disponível e rola quando necessário.

O app usa os decodificadores disponíveis no Android. Arquivos M4A com AAC são compatíveis em Android; outros codecs dentro de M4A dependem do suporte do aparelho. Arquivos protegidos por DRM ou formatos não suportados pelo sistema podem não tocar.

## Requisitos

- Android 6.0 (API 23) ou superior.
- Microfone e permissão de gravação de áudio.
- Wi‑Fi ou hotspot local conectando todos os aparelhos.
- Para compilar: Android Studio/Android SDK com Android SDK Platform 35 e Java 17 ou superior.

O projeto declara `minSdk 23`, `targetSdk 30` e `compileSdk 35`. A configuração de target SDK é antiga e deve ser revista antes de publicar na Play Store, junto às políticas e requisitos atuais do Google Play.

## Como usar

### Servidor

1. Conecte o aparelho à rede Wi‑Fi ou ligue o hotspot.
2. Abra o app e toque em **Usar este aparelho como SERVIDOR**.
3. Conceda a permissão de microfone e as permissões Bluetooth solicitadas quando aplicável.
4. Se desejar música, escolha a pasta nas configurações e inicie a reprodução.

O servidor envia a voz dele e também retransmite a voz dos participantes conectados.

### Participantes

1. Conecte o aparelho à mesma rede Wi‑Fi/hotspot do servidor.
2. Abra o app e toque em **Conectar como PARTICIPANTE**.
3. Conceda a permissão de microfone. O app procura o servidor automaticamente.

Para encerrar a sessão em um aparelho, toque em **Parar áudio**.

## Rede e áudio

- Descoberta automática por UDP na porta `45870`.
- Conexão de controle do servidor na porta TCP `45871`.
- Pacotes de áudio em UDP na porta `45872`.
- Áudio de voz em PCM mono, 16 kHz, 16 bits; o transporte divide o áudio em quadros de 25 ms.
- O servidor retransmite voz aos outros participantes. A música é enviada como uma fonte separada.
- A rede deve permitir comunicação entre os aparelhos. Algumas redes de convidados, hotspots ou roteadores com isolamento de clientes podem bloquear essa comunicação.
- O áudio trafega na rede local sem criptografia no protocolo atual. Use somente em redes confiáveis.

O Android escolhe a rota de microfone e saída de áudio conforme os dispositivos conectados e as regras do sistema. Fones Bluetooth podem usar a rota de comunicação/SCO do Android.

## Compilar

Abra a pasta no Android Studio ou use o Gradle Wrapper incluído:

```bash
./gradlew assembleDebug
```

No Windows:

```powershell
.\gradlew.bat assembleDebug
```

O APK de debug será gerado em `app/build/outputs/apk/debug/app-debug.apk`. A primeira compilação pode precisar baixar Gradle e dependências; por isso é necessária conexão com a internet.

Para gerar um APK Release local de teste:

```bash
./gradlew assembleRelease -x lintVitalRelease
```

O Gradle gera `app-release-unsigned.apk`. Assine-o com a chave local de teste antes da instalação. O repositório não inclui a chave privada; uma cópia limpa precisa de uma chave local. A assinatura de teste não deve ser usada numa publicação de produção.

Mantenha o APK final assinado em `app/build/outputs/apk/release/`, com o nome `AudioShare-<versionName>.apk` (por exemplo, `AudioShare-1.0.1.apk`). O arquivo `app-release-unsigned.apk` é a saída original do Gradle e não substitui o APK final assinado. Atualize `versionCode` e `versionName` em `app/build.gradle` ao preparar uma nova versão distribuível.

## Estrutura do projeto

```text
.
├── app/
│   ├── src/main/                 # Manifesto, Java e recursos Android
│   └── build.gradle              # Configuração do módulo Android
├── gradle/wrapper/               # Gradle Wrapper
├── build.gradle                  # Plugins Gradle
├── gradle.properties
├── settings.gradle
├── README.md
└── CHANGELOG.md
```

## Permissões

O manifesto declara permissões para microfone, rede, Wi‑Fi, manter o serviço ativo e áudio em primeiro plano. Bluetooth é usado para permitir que o Android encaminhe o áudio por fones compatíveis. A seleção de pasta de música usa o seletor de documentos do Android, que concede acesso à pasta escolhida.

## Limitações conhecidas

- É necessário manter todos os aparelhos na mesma rede local; a descoberta não atravessa sub-redes.
- O roteador/hotspot precisa permitir tráfego entre clientes.
- Qualidade, atraso e estabilidade dependem do sinal Wi‑Fi, do aparelho e da carga da rede.
- Não há criptografia do áudio em trânsito na versão atual.
- A faixa de ganho digital de voz acima de 100% pode aumentar ruído ou clipping.
- A assinatura e o target SDK atuais são para teste local, não para distribuição pública.
- Não foi fornecida uma licença de código. Antes de publicar o repositório, defina uma licença e confirme que tem os direitos de distribuir o conteúdo.
