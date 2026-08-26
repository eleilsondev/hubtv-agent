# HUB TV Agente — Etapa 1

App Android que se torna cliente ADB **de si mesmo**. Prova a arquitetura do
controle remoto: conectar ao `adbd` local, executar comandos de shell e
sobreviver ao reboot, sem PC e sem root.

Nesta etapa não há backend nem painel. O objetivo é responder uma única
pergunta no seu aparelho real: **isso funciona e aguenta um reinício?**

---

## O que cada arquivo faz

```
app/src/main/java/com/hubtv/agent/
  AdbManager.kt    A identidade RSA. Gera o par de chaves uma vez, guarda no
                   disco do app. É o "número de série" que aparece na TV.
  Adb.kt           Todas as operações: ligar depuração, parear, conectar,
                   rodar shell, fixar a porta 5555.
  AgentService.kt  Serviço em primeiro plano. Reconecta sozinho após o boot,
                   com recuo progressivo. Onde o check-in vai nascer.
  BootReceiver.kt  Acorda o serviço no BOOT_COMPLETED.
  MainActivity.kt  A tela de teste (parear, conectar, testar poderes).
  Registro.kt      Log em memória que a tela observa.
```

---

## Como compilar

**Caminho recomendado — Android Studio**

1. Abra o Android Studio › *Open* › selecione a pasta `agent/`.
2. Ele baixa o Gradle wrapper, o SDK e as dependências sozinho.
3. *Build › Build APK(s)*. O `.apk` sai em `app/build/outputs/apk/debug/`.

**Por linha de comando** (se já tiver o Android SDK e o Gradle)

```
cd agent
gradle wrapper          # só na primeira vez, gera o ./gradlew
./gradlew assembleDebug  # (gradlew.bat no Windows)
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

> As dependências `libadb-android`, `conscrypt` e `bouncycastle` vêm do
> Maven Central e do JitPack — precisa de internet na primeira build.

---

## Como testar no aparelho

Faça no seu Stick HD, que é onde a dúvida real mora.

### 1. Instalar
Pelo HubTV que já temos, ou direto:
```
adb install app-debug.apk
adb shell pm grant com.hubtv.agent android.permission.WRITE_SECURE_SETTINGS
```
A segunda linha é a que permite o agente religar a depuração após o boot.
No fluxo de produção, o deploy do HubTV faz isso.

### 2. Primeira conexão (a única vez que precisa de gente)
1. Abra o **HUB TV Agente** na TV.
2. Toque em **Ligar depuração**.
3. Na TV, vá em *Depuração sem fio › Parear dispositivo por Wi-Fi*.
   Anote a **porta** e o **código**.
4. No app, preencha porta + código e toque **Parear**.
5. Um diálogo do sistema aparece com um "número de série" — é a chave RSA
   do agente. Marque **sempre permitir** e confirme.
6. Toque **Conectar** → depois **Testar poderes**.

Se o registro mostrar o modelo, `uid=2000(shell)` e a lista de pacotes, o
agente tem shell. **Metade da prova está feita.**

### 3. A prova que importa — o reboot
1. (Opcional, se o firmware aceitar) toque **Fixar porta 5555**.
2. Reinicie o aparelho: `adb reboot` ou pelo controle.
3. **Não toque em nada.** Espere ~1 minuto.
4. Abra o app de novo e olhe o registro.

O esperado, sem nenhuma intervenção:
```
boot detectado (android.intent.action.BOOT_COMPLETED)
aguardando o sistema assentar (45s)
reconexao: tentativa 1
de pe outra vez, sem PC e sem dialogo
```

Se aparecer isso, **a arquitetura inteira está validada** e podemos construir
o backend e o painel com confiança. Se não, o registro diz onde travou — me
mande o texto e eu ajusto.

---

## O que pode dar errado (e o que significa)

| Sintoma | Causa provável | Caminho |
|---|---|---|
| Diálogo não aparece ao parear | firmware sem depuração sem fio | testar outro modelo |
| Conecta, mas cai no reboot | `adb_keys` limpo no boot | fixar porta / testar outro modelo |
| "sem WRITE_SECURE_SETTINGS" | permissão não concedida | rodar o `pm grant` acima |
| Reconecta só se abrir o app | serviço morto pelo sistema | ajustar bateria do agente (fase 3 do HubTV) |

Essas são exatamente as incertezas que a Etapa 1 existe para eliminar antes
de investirmos no resto.
