# HUB TV — contexto do projeto (handoff para o Claude Code local)

> Este arquivo é lido automaticamente pelo Claude Code ao abrir a pasta.
> Ele resume tudo que já foi decidido e construído, para que uma sessão
> nova (local, na máquina do desenvolvedor) continue de onde paramos, sem
> perder o histórico. Idioma do projeto: **português**.

## Quem é o dono e o objetivo

Revendedor/provisionador de TV Box Android ("HUB TV"). Ele configura e
vende aparelhos e precisa de: (1) provisionar cada aparelho rápido e igual,
(2) dar **suporte remoto** e **relatórios** da frota **sem estar na mesma
rede** e **sem PC na casa do cliente**.

Arquitetura-alvo: **agente no aparelho → backend/painel web (Laravel) → relatórios e comandos**.

## As três fases

### Fase A — Provisionamento por ADB (CONCLUÍDA)
Script Windows `.bat` (`deploy_hubtv.bat`) que configura a TV via ADB-over-WiFi
num fluxo linear de 8 etapas ("esteira"), com opção de pular cada etapa:
- Conecta por mDNS e, se falhar, manual (IP:porta).
- Instala launcher `com.rightside.launcher` e apps: SmartTube
  `org.smarttube.stable`, UnitVIP `com.global.unitviptv`, tvQuickActions
  `dev.vodik7.tvquickactions`.
- Desativa bloatware e o launcher do Google.
- Concede permissões (inclui "fontes desconhecidas" p/ auto-update dos apps).
- Restaura backups do tvQuickActions (`tvQA_*.zip` via pull/push).
- **Lição aprendida:** `pm hide` FALHA (exige MANAGE_USERS). Usar
  `pm disable-user` + `cmd package suspend`.
- Detecção de pastas de APK clonado (split APK) e nome dinâmico de arquivo
  (via Content-Disposition) para versões novas.

### Fase B — GUI PowerShell (CONCLUÍDA)
`HubTV.ps1` (WPF) + `Compilar.bat`/`Compilar.ps1` que gera um `.exe` via
**ps2exe**, embutindo o payload do adb (todo o adb vai pra dentro do `.exe`).
Correções aplicadas: bug de captura de CR, bug de escopo de closure
(`$estado = @{ ok=$false }` em vez de `$script:SelOk`), checagem de pasta
gravável. As pastas `backup/` e `apks/` são criadas automaticamente.

### Fase C — Agente Android (EM ANDAMENTO — este repositório)
APK `com.hubtv.agent` que embute um **cliente ADB** e se conecta ao **adbd
do próprio aparelho** em `127.0.0.1`, roda comandos de shell e **reconecta
sozinho após o boot, sem PC**. É a "Etapa 1" que prova a arquitetura de frota.

## Como o agente funciona (o coração da Fase C)

- Lib: `com.github.MuntashirAkon:libadb-android:3.1.1`, subclasse de
  `AbsAdbConnectionManager`. Conexão para `127.0.0.1` — o aparelho é ao
  mesmo tempo o "PC" e o alvo → funciona em qualquer rede/IP, nada aberto pra fora.
- **Identidade RSA**: o app se apresenta como um "computador". Ao marcar
  "sempre permitir" no diálogo da TV, o Android grava a chave pública em
  `/data/misc/adb/adb_keys`, que **sobrevive ao reboot**. A chave é gerada
  UMA vez e guardada em `filesDir` (`adb_key.pk8` + `adb_cert.der`).
- **Certificado X.509**: montado **à mão em DER puro**, só com `java.security`.
  Motivo (custou 6 builds): BouncyCastle quebra em runtime
  (`NoClassDefFoundError` de classes ASN.1 — EdEC/OIW ObjectIdentifiers) e
  `sun-security-android` é descartado pelo AGP (namespace `sun.*`).
  **NÃO reintroduzir dependências de cripto.** Ver `AdbManager.kt`.
- **Dois caminhos de conexão** (`Adb.conectar()` tenta nesta ordem):
  1. **TCP 5555 (legado)**: no HubTV roda-se `adb tcpip 5555` uma vez;
     conectar dispara o diálogo clássico "sempre permitir" de **UM toque, SEM
     código**. É o fluxo preferido — o usuário rejeitou o pareamento por
     código como inviável na TV (sem multi-janela).
  2. **Wireless debugging TLS (Android 11+)**: `autoConnect` via mDNS interno;
     exige pareamento (porta+código) uma vez. Tratado como opcional.
- **Sobrevivência ao desligamento 100% (boot frio, não reboot):**
  `service.adb.tcp.port` é propriedade de runtime → some no boot frio.
  Quem sobrevive é `persist.adb.tcp.port` (init restaura a cada boot nos
  firmwares que respeitam). `Adb.fixarPorta5555()` grava as duas e confere.
- **Religar depuração**: `WRITE_SECURE_SETTINGS` (via `pm grant`) permite
  `settings put global adb_wifi_enabled 1` após o boot.
- **Boot**: `BootReceiver` (BOOT_COMPLETED / LOCKED_BOOT_COMPLETED /
  MY_PACKAGE_REPLACED) inicia `AgentService` (foreground). `manterConexao()`
  espera 45s o sistema assentar, religa a depuração e reconecta com backoff.
- **Antitela-preta**: `AgentApp` instala um handler global de exceção que
  grava o crash em `filesDir/ultimo_erro.txt`; a `MainActivity` mostra o erro
  da execução anterior ao reabrir (a TV não tem logcat acessível). Toda a
  cripto/ADB roda em `Dispatchers.IO` com try/catch.

## Mapa dos arquivos (Fase C)

- `app/src/main/java/com/hubtv/agent/AdbManager.kt` — identidade RSA + cert
  DER puro. **Arquivo mais delicado.**
- `.../Adb.kt` — `conectar()`, `parear()`, `shell()`, `ligarDepuracaoSemFio()`,
  `fixarPorta5555()`. `HOST = "127.0.0.1"`.
- `.../AgentService.kt` — serviço foreground; laço de reconexão pós-boot.
- `.../BootReceiver.kt` — starta o serviço no boot.
- `.../AgentApp.kt` — handler de crash em arquivo.
- `.../LauncherActivity.kt` — **tela inicial (HOME)** da TV. Layout vertical:
  barra superior + banner de tamanho fixo + carrossel horizontal de apps.
  Sem menu de categorias. Atalhos do sistema usam icones SVG (ic_wifi,
  ic_bluetooth, ic_settings). Config vem do painel via check-in e fica em
  SharedPreferences `hubtv_launcher`. Tela de bloqueio e tela de ativacao
  (mostra codigo de 8 chars gerado localmente). Suporta notificacoes do
  servidor (AlertDialog sequencial). Tecla MENU abre a MainActivity.
  - **Barra superior em 3 slots** (`slot_esquerda`/`slot_centro`/
    `slot_direita`, mesma largura). `reposicionarTopbar()` move os blocos
    `bloco_identidade` (logo+nome), `sistema_atalhos` e `relogio_container`
    para o slot que o painel mandou (`posicao_logo`, `posicao_atalhos`,
    `posicao_relogio`). Blocos no mesmo slot ficam lado a lado.
    O valor legado `canto` equivale a `esquerda`.
  - **Nome escrito** so aparece se o painel mandar `exibir_nome: true`. No
    modo automatico o painel devolve `false` quando ha logo, para nao
    duplicar a marca.
  - **Banner de tamanho FIXO em dp** (`largura_banner` x `altura_banner`),
    nao proporcao. O painel ja recorta a arte exatamente nessa medida
    (`imagemRecortada()`), entao nada estica nem achata. Carrossel com
    crossfade entre `banner_imagem_a`/`banner_imagem_b` a cada
    `banner_intervalo` segundos, com bolinhas indicadoras. O banner e
    vitrine pura: nao abre app e nao pega foco.
  - **Apps em carrossel horizontal** (LinearLayoutManager HORIZONTAL) com
    cards de largura fixa (112dp) — antes era grid de 5 colunas, que
    achatava os icones quando o banner crescia.
- `.../MainActivity.kt` — UI de controle do agente (botões: Ligar depuração,
  Parear, Conectar, Testar poderes, Fixar porta 5555, Limpar). Registro na tela.
- `.../Registro.kt` — log em memória observável, exibido na UI.
- `app/build.gradle.kts` — deps: libadb-android 3.1.1, conscrypt-android 2.5.3.
  **Sem BouncyCastle, sem sun-security.** minSdk 21, targetSdk 34,
  coreLibraryDesugaring, viewBinding.
- `AndroidManifest.xml` — `.AgentApp`, WRITE_SECURE_SETTINGS,
  FOREGROUND_SERVICE_DATA_SYNC, RECEIVE_BOOT_COMPLETED, launcher leanback.

## Build na nuvem (sem Android Studio local)

`.github/workflows/build.yml` compila no GitHub Actions a cada push na `main`
(ignora mudanças só em `.md`) e publica uma Release `build-<n>` com
`HubTVAgente-b<n>.apk`. **O repositório precisa ser PÚBLICO** — a conta tem
orçamento de Actions em $0 com "Stop usage: Yes", e repo público não consome
minutos. Estado atual: **build #7** adiciona `persist.adb.tcp.port` para
sobreviver ao desligamento total.

## Onde paramos / próximos passos

- **Etapa 2 (CONCLUÍDA):** check-in periódico + painel Laravel com dashboard,
  gestão de dispositivos, usuários, comandos remotos.
- **Etapa 3 (CONCLUÍDA):** fila de comandos (shell, install, reboot, bloquear,
  desbloquear, atualizar_launcher, atualizar_agente) com report de resultado
  pelo APK. `Comandos.kt` executa cada tipo via `Adb.shell()` e reporta o
  resultado ao painel. Auto-update: dispositivo baixa novo APK de URL e faz
  `pm install -r` sobre si mesmo.
- **Launcher (v2 CONCLUIDO):** LauncherActivity como HOME da TV Box.
  Icones SVG profissionais (WiFi, Bluetooth, Config, Wrench). Ativacao por
  codigo de 8 chars gerado no dispositivo — revendedor ativa no painel
  consumindo credito. Notificacoes do servidor exibidas como dialogo.
  APK upload no cadastro de apps.
- **Launcher (v3 CONCLUIDO):** layout totalmente configuravel pelo painel.
  Barra superior em 3 slots com posicao independente para logo, relogio e
  atalhos do sistema; nome escrito some sozinho quando ha logo; banner com
  tamanho fixo em dp e carrossel automatico com crossfade; apps em
  carrossel horizontal. Campos novos no perfil: `largura_banner`,
  `banner_intervalo`, `posicao_relogio`, `posicao_atalhos`, `exibir_nome`.
  O banner deixou de abrir app (`pacote_alvo` saiu do JSON da API).
- **Painel v2:** Planos, Creditos (XXXX-XXXX), Ativacao (revendedor consome
  credito com codigo do dispositivo), Central de Comandos (modelos + shell
  manual), Notificacoes (envio por dispositivo). Auto-push launcher update
  quando perfil muda. Codigo de ativacao gerado no device, sem enrollment.

## Convenções

- Idioma de código, comentários, commits e UI: **português** (sem acentos em
  strings que vão para a TV, por segurança de encoding).
- Nomes de identificadores em português (ex.: `conectar`, `manterConexao`,
  `fixarPorta5555`, `Registro`, `NOME_NO_DIALOGO`).
- Commits descritivos em português explicando o "porquê".
