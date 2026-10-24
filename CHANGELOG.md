# Changelog

## 1.0.2 — 2026-10-24

### Adicionado

- Opção **Sobre** nas configurações, com a versão instalada e consulta manual da release pública mais recente no GitHub.
- Limite local de uma tentativa de consulta a cada 24 horas; a página da release pode ser aberta quando uma versão mais nova está disponível.

## 1.0.1 — 2026-10-24

### Adicionado

- Buffer de reprodução adaptativo por fonte de áudio e indicador do buffer nas métricas do participante.
- Seleção direta de uma faixa na lista de músicas do servidor.
- Medição do nível captado pelo microfone, exibida junto ao controle de voz enviada.

### Alterado

- Lista musical em linhas, dimensionada para ocupar o espaço disponível entre os controles superiores e o player fixo no rodapé.
- Player e lista agrupados; seleção de pasta movida para Configurações e controles de reprodução mantidos acessíveis no rodapé.
- Player com ícones de reprodução e pausa, controle compacto de aleatório e destaque teal consistente com o ícone do app.
- Slider de música com escala de `0–100%` para ganho de `0–25%`, padrão de `10%` e reprodução aleatória ativada por padrão.
- Indicadores de status simplificados e aparelhos conectados agrupados em um card.

### Corrigido

- Bloqueio de uma nova inicialização como servidor quando outro servidor AudioShare já está ativo na rede.
- Atualização da playlist sem interromper a faixa atual quando ela continua presente na pasta selecionada.
- Layout adaptado para alturas de tela diferentes, com o topo rolável e a biblioteca ocupando o espaço flexível.

## 1.0.0

- Primeira versão estável para instalação direta e testes em rede Wi‑Fi local.
- Intercomunicação por voz entre servidor e participantes, com reprodução de música no servidor.
