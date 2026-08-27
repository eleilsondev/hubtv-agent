# Etapa 2 — Painel Laravel + check-in do agente

Este documento é o **contrato** entre o agente (APK) e o painel (Laravel).
Os dois lados o seguem à risca. O lado do agente já está implementado em
`CheckIn.kt`/`Config.kt`. Aqui está o lado do servidor, pronto para colar.

Fluxo do dispositivo em produção: casa do cliente, sem PC. O agente já sabe
falar HTTPS; o painel mora no seu **VPS** (dev no XAMPP local).

---

## 1. Contrato da API

Autenticação em duas camadas:

- **Inscrição** (uma vez por aparelho): protegida por um **segredo
  compartilhado** no cabeçalho `X-Enroll-Key`. O painel cria o aparelho e
  devolve um **token** único dele.
- **Check-in** (periódico): autenticado por `Authorization: Bearer <token>`.

### `POST /api/dispositivos/registrar`
Cabeçalho: `X-Enroll-Key: <segredo>`
Corpo:
```json
{
  "device_id": "a1b2c3d4e5f6",
  "modelo": "MiBox4",
  "fabricante": "Xiaomi",
  "android": "9",
  "sdk": 28,
  "adb_ok": true,
  "apps": [{ "pkg": "org.smarttube.stable", "versao": "0.9.5" }]
}
```
Resposta `200`:
```json
{ "token": "H8s...60 chars...", "novo": true }
```

### `POST /api/dispositivos/checkin`
Cabeçalho: `Authorization: Bearer <token>`
Corpo: **o mesmo retrato** do registrar (sem `device_id`, que já é do token).
Resposta `200`:
```json
{ "ok": true, "comandos": [] }
```
O array `comandos` é a ponte para a Etapa 3 (fila de comandos). Por ora vazio.

---

## 2. Criar o projeto (XAMPP local)

Pré-requisitos: PHP 8.2+ e **Composer**. O XAMPP já traz PHP; instale o
Composer (getcomposer.org). MySQL do XAMPP na porta 3306.

```bash
cd C:\xampp\htdocs
composer create-project laravel/laravel painel-hubtv
cd painel-hubtv
php artisan install:api      # habilita routes/api.php (Laravel 11+)
```

No **phpMyAdmin** (http://localhost/phpmyadmin) crie o banco `hubtv`.
No `.env`:
```
DB_CONNECTION=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=hubtv
DB_USERNAME=root
DB_PASSWORD=

HUBTV_ENROLL_KEY=troque-esta-chave-compartilhada
```
> Esse `HUBTV_ENROLL_KEY` tem que ser **idêntico** ao `Config.CHAVE_INSCRICAO`
> do APK.

---

## 3. Migration — `database/migrations/xxxx_create_dispositivos_table.php`

```php
<?php
use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration {
    public function up(): void {
        Schema::create('dispositivos', function (Blueprint $t) {
            $t->id();
            $t->string('device_id')->unique();
            $t->string('token', 80)->unique()->nullable();
            $t->string('modelo')->nullable();
            $t->string('fabricante')->nullable();
            $t->string('android')->nullable();
            $t->integer('sdk')->nullable();
            $t->string('ip')->nullable();
            $t->boolean('adb_ok')->default(false);
            $t->json('apps')->nullable();
            $t->timestamp('ultimo_checkin')->nullable();
            $t->timestamps();
        });
    }
    public function down(): void { Schema::dropIfExists('dispositivos'); }
};
```

## 4. Model — `app/Models/Dispositivo.php`

```php
<?php
namespace App\Models;
use Illuminate\Database\Eloquent\Model;

class Dispositivo extends Model {
    protected $fillable = [
        'device_id','token','modelo','fabricante','android','sdk',
        'ip','adb_ok','apps','ultimo_checkin',
    ];
    protected $casts = [
        'apps' => 'array',
        'adb_ok' => 'boolean',
        'ultimo_checkin' => 'datetime',
    ];

    // Online = deu check-in nos últimos 10 minutos.
    public function getOnlineAttribute(): bool {
        return $this->ultimo_checkin
            && $this->ultimo_checkin->gt(now()->subMinutes(10));
    }
}
```

## 5. Config — `config/hubtv.php`

```php
<?php
return [
    'enroll_key' => env('HUBTV_ENROLL_KEY', ''),
];
```

## 6. Middleware — `app/Http/Middleware/TokenDispositivo.php`

```php
<?php
namespace App\Http\Middleware;
use App\Models\Dispositivo;
use Closure;
use Illuminate\Http\Request;

class TokenDispositivo {
    public function handle(Request $request, Closure $next) {
        $token = $request->bearerToken();
        $dispositivo = $token ? Dispositivo::where('token', $token)->first() : null;
        if (!$dispositivo) {
            return response()->json(['erro' => 'token invalido'], 401);
        }
        $request->attributes->set('dispositivo', $dispositivo);
        return $next($request);
    }
}
```

Registre o alias em `bootstrap/app.php` (Laravel 11+), dentro de
`->withMiddleware(function (Middleware $middleware) { ... })`:
```php
$middleware->alias([
    'dispositivo.token' => \App\Http\Middleware\TokenDispositivo::class,
]);
```

## 7. Controller — `app/Http/Controllers/DispositivoController.php`

```php
<?php
namespace App\Http\Controllers;
use App\Models\Dispositivo;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

class DispositivoController extends Controller {

    public function registrar(Request $r) {
        if (!hash_equals(config('hubtv.enroll_key'), (string) $r->header('X-Enroll-Key'))) {
            return response()->json(['erro' => 'chave de inscricao invalida'], 403);
        }
        $dados = $r->validate([
            'device_id' => 'required|string|max:190',
            'modelo' => 'nullable|string',
            'fabricante' => 'nullable|string',
            'android' => 'nullable|string',
            'sdk' => 'nullable|integer',
            'adb_ok' => 'boolean',
            'apps' => 'nullable|array',
        ]);

        $dispositivo = Dispositivo::firstOrNew(['device_id' => $dados['device_id']]);
        $novo = !$dispositivo->exists;
        if (!$dispositivo->token) {
            $dispositivo->token = Str::random(60);
        }
        $dispositivo->fill($dados);
        $dispositivo->ip = $r->ip();
        $dispositivo->ultimo_checkin = now();
        $dispositivo->save();

        return response()->json(['token' => $dispositivo->token, 'novo' => $novo]);
    }

    public function checkin(Request $r) {
        $dispositivo = $r->attributes->get('dispositivo');
        $dados = $r->validate([
            'modelo' => 'nullable|string',
            'fabricante' => 'nullable|string',
            'android' => 'nullable|string',
            'sdk' => 'nullable|integer',
            'adb_ok' => 'boolean',
            'apps' => 'nullable|array',
        ]);
        $dispositivo->fill($dados);
        $dispositivo->ip = $r->ip();
        $dispositivo->ultimo_checkin = now();
        $dispositivo->save();

        // Etapa 3: aqui sairá a fila de comandos pendentes do aparelho.
        return response()->json(['ok' => true, 'comandos' => []]);
    }
}
```

## 8. Rotas da API — `routes/api.php`

```php
<?php
use App\Http\Controllers\DispositivoController;
use Illuminate\Support\Facades\Route;

Route::post('/dispositivos/registrar', [DispositivoController::class, 'registrar']);

Route::middleware('dispositivo.token')->group(function () {
    Route::post('/dispositivos/checkin', [DispositivoController::class, 'checkin']);
});
```

## 9. Dashboard simples — `routes/web.php` + view

`routes/web.php`:
```php
use App\Models\Dispositivo;
use Illuminate\Support\Facades\Route;

Route::get('/painel', function () {
    $dispositivos = Dispositivo::orderByDesc('ultimo_checkin')->get();
    return view('painel', compact('dispositivos'));
});
```

`resources/views/painel.blade.php`:
```blade
<!doctype html>
<html lang="pt-br">
<head>
  <meta charset="utf-8">
  <title>Frota HUB TV</title>
  <style>
    body{font-family:system-ui,Arial;background:#0e1116;color:#e6edf3;margin:0;padding:24px}
    h1{font-size:20px} table{border-collapse:collapse;width:100%;margin-top:16px}
    th,td{padding:8px 10px;border-bottom:1px solid #22272e;text-align:left;font-size:14px}
    .on{color:#3fb950;font-weight:bold} .off{color:#f85149;font-weight:bold}
  </style>
</head>
<body>
  <h1>Frota HUB TV — {{ $dispositivos->count() }} aparelho(s)</h1>
  <table>
    <tr><th>Estado</th><th>Modelo</th><th>Android</th><th>IP</th><th>ADB</th><th>Último check-in</th></tr>
    @foreach ($dispositivos as $d)
      <tr>
        <td class="{{ $d->online ? 'on' : 'off' }}">{{ $d->online ? 'ONLINE' : 'offline' }}</td>
        <td>{{ $d->fabricante }} {{ $d->modelo }}</td>
        <td>{{ $d->android }}</td>
        <td>{{ $d->ip }}</td>
        <td>{{ $d->adb_ok ? 'ok' : '—' }}</td>
        <td>{{ optional($d->ultimo_checkin)->diffForHumans() }}</td>
      </tr>
    @endforeach
  </table>
</body>
</html>
```

---

## 10. Subir e testar

```bash
php artisan migrate
php artisan serve      # http://127.0.0.1:8000
```

Teste a inscrição sem o aparelho (simulando o agente):
```bash
curl -X POST http://127.0.0.1:8000/api/dispositivos/registrar \
  -H "X-Enroll-Key: troque-esta-chave-compartilhada" \
  -H "Content-Type: application/json" \
  -d '{"device_id":"teste123","modelo":"MiBox4","android":"9","sdk":28,"adb_ok":true,"apps":[]}'
```
Deve voltar `{"token":"...","novo":true}`. Abra **http://127.0.0.1:8000/painel**
e o aparelho de teste aparece como ONLINE.

---

## 11. Notas de produção (VPS)

- Sirva por **HTTPS** (Let's Encrypt). O agente usa `usesCleartextTraffic`
  só para o dev; em produção use `https://` no `Config.BASE_URL`.
- Aponte o vhost do Apache/Nginx para a pasta `public/` do Laravel.
- `php artisan migrate --force` no deploy.
- Guarde `HUBTV_ENROLL_KEY` no `.env` do servidor — nunca no git.
- No APK de produção, troque `Config.BASE_URL` para a URL do VPS e
  `Config.CHAVE_INSCRICAO` para o mesmo valor do `.env`, e gere a build.
