# ZarkTV Launcher (TV Box)

Tela inicial leve para TV Box, ligada ao painel ZarkTV Cloud (aba **Boxes**).
**Um APK só** para box antiga (Android 4.4, MXQ de 1 GB) e para as novas.
Ela descobre sozinha o que a box permite:

| Modo | Quando | O que faz |
|---|---|---|
| **dona** | Android 5+ configurada uma vez com `ferramentas/configurar-box.bat` | instala e atualiza sem clique, esconde apps, bloqueia instalação de fora, fica fixa como tela inicial |
| **root** | box com root (muitas MXQ) | quase tudo do modo dona, usando o `su` |
| **simples** | nenhum dos dois | tela inicial normal; instalar e atualizar pede um clique do cliente |

O que vem na tela: relógio, sinal do Wi-Fi/cabo, banners do painel, **YouTube**, **ZarkPlay**,
**Lojinha ZarkTV**, Configurações e Todos os apps. Box sem YouTube ou ZarkPlay instala pelo painel
("Apps que toda box tem").

**Box do cliente × box minha** (escolhido no painel, box por box): a do cliente fica livre;
a minha tem controle total (não desinstala, só entra o que vem do painel, bloqueio com Pix opcional).

- APK (link fixo): https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/launcher/zarktv-launcher.apk
- Compila sozinho no GitHub (`.github/workflows/launcher.yml`) quando muda algo em `launcher/`.
- Painel: pasta `painel-boxes/` deste repositório.
