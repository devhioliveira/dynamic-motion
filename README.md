# Dynamic Motion beta

O Dynamic Motion 3.0 para LSPosed desenha a ilha no SystemUI da Samsung e substitui a cápsula superior da Now Bar enquanto a ilha está visível. O APK desta versão usa o renderizador fornecido pelo Dynamic Motion 2.0 e adapta os cartões aos dados de atividades em andamento do SystemUI. A integração não exige Acessibilidade nem acesso a notificações.

Este repositório contém o código da integração e os scripts de build. O build usa sua cópia do APK 2.0 para obter o renderizador e os recursos visuais originais.

## Compilar

Instale o JDK 21, o Python 3.11 ou superior e, pelo SDK Manager do Android Studio, os pacotes **Android SDK Platform 35** e **Android SDK Build-Tools 36.0.0**.

Na raiz do repositório, execute no Windows (PowerShell):

```powershell
py -3 build.py "C:\caminho\Dynamic Motion(2.0).apk" --sdk "$env:LOCALAPPDATA\Android\Sdk"
```

No Linux ou macOS, indique o caminho do SDK da sua máquina:

```bash
python3 build.py "/caminho/Dynamic Motion(2.0).apk" --sdk "/caminho/Android/Sdk"
```

Se `ANDROID_HOME` já aponta para o SDK, pode omitir `--sdk`. O script baixa e valida as dependências na primeira compilação. Ele espera a edição 2.0 usada para desenvolver esta integração, identificada pelo SHA-256 `682fef4c47d0a9b2069bd04bfb7a1485386b4481501b09844e5e7c668d1b6313`.

O APK sai em `build/Dynamic-Motion-3.0-Native-LSPosed.apk`. O script gera uma chave local de teste em `.signing/dynamic-motion.p12` e a reutiliza nos próximos builds. Guarde a chave se quiser instalar suas próximas compilações como atualizações.

## Instalar

1. Desative o OneUisland no LSPosed. Se você tiver o Dynamic Motion original instalado, guarde suas configurações antes de desinstalá-lo, pois ele usa o mesmo identificador de pacote e uma assinatura diferente.
2. Instale o APK gerado. Habilite o módulo no LSPosed e selecione apenas **System UI** (`com.android.systemui`) no escopo.
3. Reinicie e abra uma atividade que o Samsung já exiba na cápsula do topo, como música ou cronômetro.

O módulo tem como alvo aparelhos Samsung com Android 15 ou superior e depende dos nomes internos do SystemUI usados pela ROM. O APK foi compilado e verificado. A compatibilidade em aparelho físico ainda precisa ser confirmada. O módulo cobre a cápsula superior, e a biblioteca de plugins e configurações do 2.0 fica fora deste porte.

`src/` contém a integração com LSPosed, o adaptador de atividades e a janela da ilha. `stubs/` contém apenas assinaturas usadas na compilação. O script `adapt_renderer.py` adapta três construtores para receber o contexto do SystemUI, e o build insere o código compilado no APK gerado.
