# ZarkTV TV e ZarkTV Mobile (versão de teste)

## O que mudou

O mesmo código do ZarkTV agora gera **três apps**:

| Versão | Arquivo | Pacote | Para quê |
|---|---|---|---|
| ZarkTV (o de sempre) | `zarktv.apk` | `io.github.axon07.channelbox` | App dos clientes. **Não mudou nada.** |
| ZarkTV TV | `zarktv-tv.apk` | `cfd.zarktvqx.tv` | TV Box, Android TV, Google TV, Fire Stick |
| ZarkTV Mobile | `zarktv-mobile.apk` | `cfd.zarktvqx.mobile` | Celular e tablet |

Como TV e Mobile têm pacote próprio, eles **instalam ao lado** do app atual. Dá para testar sem
desinstalar nada e sem mexer nos clientes.

Os três continuam ligados ao mesmo servidor: lista de servidores e DNS no
`https://tv.zarktvqx.cfd/config.json`, login por usuário e senha, troca automática de DNS,
avisos, banners, checkout Pix e tudo que o painel ZarkTV Cloud manda.

### ZarkTV TV
- Sempre no modo controle remoto, **mesmo em TV Box barata que se apresenta como celular**
  (antes o login abria o teclado direto nessas caixas).
- Sempre deitado. Aparece no menu da Android TV e também no launcher de TV Box.

### ZarkTV Mobile
- Sempre no modo toque.
- Gira a tela (em pé ou deitado), conforme o celular.
- Não aparece no menu de Android TV.
- Se estiver passando um canal/filme e o cliente apertar o botão Início, vira **janelinha (PiP)**.
  Fechando a janelinha, o vídeo para (não fica gastando internet).

### Atualização
- Cada app procura só a atualização dele (`version-tv.json`, `version-mobile.json`).
- TV e Mobile já vêm com "versões de teste" ligadas, porque por enquanto só existem no beta.

## Como gerar os APKs

1. Envie este ramo para o GitHub no ramo **`beta`** (nunca direto na `main`).
2. O GitHub Actions compila sozinho e publica em **Releases → beta**:
   - `https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/beta/zarktv-tv.apk`
   - `https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/beta/zarktv-mobile.apk`
   - `https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/beta/zarktv.apk` (o de sempre, versão de teste)
3. Os três saem assinados com a mesma chave `zarktv-release.jks` (segredos do GitHub).

No ramo `main` nada muda: continua saindo só o `zarktv.apk` dos clientes.

Compilar no PC (precisa Android Studio/SDK):

```bash
./gradlew assembleTvRelease       # ZarkTV TV
./gradlew assembleMobileRelease   # ZarkTV Mobile
./gradlew assembleUnicoRelease    # ZarkTV de sempre
```

## Lista de testes (marque ao testar)

| Teste | Fire Stick | TV Box fraca | Android TV | Moto (celular) | Tablet |
|---|---|---|---|---|---|
| Instala ao lado do ZarkTV atual | ☐ | ☐ | ☐ | ☐ | ☐ |
| Ícone aparece no lugar certo | ☐ | ☐ | ☐ | ☐ | ☐ |
| Login: escolhe servidor, usuário e senha | ☐ | ☐ | ☐ | ☐ | ☐ |
| Login na TV: controle chega no campo sem abrir teclado sozinho | ☐ | ☐ | ☐ | — | — |
| Carrega canais, filmes e séries | ☐ | ☐ | ☐ | ☐ | ☐ |
| Canal abre rápido / troca de canal | ☐ | ☐ | ☐ | ☐ | ☐ |
| Filme: retomar de onde parou | ☐ | ☐ | ☐ | ☐ | ☐ |
| Favoritos continuam depois de "Atualizar lista" | ☐ | ☐ | ☐ | ☐ | ☐ |
| Celular em pé e deitado | — | — | — | ☐ | ☐ |
| Janelinha (PiP) ao apertar Início com vídeo passando | — | — | — | ☐ | ☐ |
| Ligação recebida: vídeo pausa e volta | — | — | — | ☐ | — |
| Aviso de vencimento e banners do painel | ☐ | ☐ | ☐ | ☐ | ☐ |
| Aviso de atualização (sair nova versão beta) | ☐ | ☐ | ☐ | ☐ | ☐ |
| Internet ruim: mostra erro e "tentar de novo" (sem carregar infinito) | ☐ | ☐ | ☐ | ☐ | ☐ |

## Próximas etapas sugeridas (do dossiê, em cima deste código)

1. **Senha protegida**: hoje usuário/senha ficam salvos no armazenamento normal do app. Passar
   para o cofre do Android (Keystore).
2. **Banco sem apagar dados**: o banco usa `fallbackToDestructiveMigration()`; se uma atualização
   do banco der erro, apaga tudo (inclusive favoritos). Trocar por migrações testadas.
3. **Telas próprias**: com as versões separadas, dá para fazer telas diferentes de verdade
   (ex.: barra de abas embaixo no Mobile, guia de programação maior na TV) usando `Aparelho.soTv` /
   `Aparelho.soCelular`.
4. **Medição do player**: o diagnóstico já mede abertura e travadas; juntar num relatório por aparelho.
5. Quando os testes estiverem ok: publicar TV e Mobile também no ramo `main` para os clientes.

## Arquivos alterados neste passo

- `app/build.gradle.kts` – as três versões (unico, tv, mobile)
- `app/src/main/AndroidManifest.xml` – o ícone de abrir saiu daqui…
- `app/src/unico|tv|mobile/AndroidManifest.xml` – …e cada versão declara o seu
- `app/src/tv|mobile/res/values/strings.xml` – nome "ZarkTV TV" / "ZarkTV Mobile"
- `app/src/main/java/.../system/Aparelho.kt` – diz em qual aparelho o app está (novo)
- `app/src/main/java/.../ui/SetupScreen.kt` – login usa essa regra
- `app/src/main/java/.../MainActivity.kt` – janelinha (PiP) no Mobile
- `app/src/main/java/.../data/Updater.kt` – cada app busca a própria atualização
- `.github/workflows/*.yml` – beta gera os três APKs; main continua igual
