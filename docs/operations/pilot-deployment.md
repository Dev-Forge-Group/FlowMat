# 파일럿 배포 절차

> **현행 문서** · 2026-11-28. [배포안 비교](pilot-deployment-options.md)에서 권장한 A(VM 한 대)를 띄우는 순서다. **아직 실제로 띄운 적은 없다.** 이 세션에서 버리는 컨테이너로 리허설만 했다(§7). 클라우드 계정, VM, 도메인, 메일 발송 서비스는 승인한 뒤 사람이 마련한다.

## 1. 구성

[`deploy/pilot/`](../../deploy/pilot)에 있다. 한 기계에서 Docker Compose로 띄운다.

| 서비스 | 이미지 | 바깥에서 닿는가 | 자료 |
|---|---|---|---|
| `caddy` | `caddy:2.10` | 80, 443만 연다. 인증서를 받아 갱신하고, 화면 파일을 내주고, `/api`를 앱에 넘긴다 | 인증서(볼륨 `caddydata`) |
| `app` | `eclipse-temurin:21-jre` + 묶음의 `app.jar` | 닿지 않는다. 8080(API)과 8081(관리용 actuator) 모두 Compose 망 안에만 있다 | 첨부 파일(`./uploads`) |
| `postgres` | `postgres:16` | 닿지 않는다 | 볼륨 `pgdata` |
| `redis` | `redis:7.4`(AOF 켬) | 닿지 않는다 | 볼륨 `redisdata` |

- **앱 실행:** `prod` 프로필로 뜨고, root가 아닌 사용자(1000)로 돈다.
  - 약한 JWT 비밀값, https가 아닌 주소, 빈 메일 설정이면 기동하지 않는다(`ProductionConfigValidator`).
  - 데모 계정과 데모 자료는 들어가지 않는다.
- **설정:** 모두 `deploy/pilot/.env` 하나에 둔다. 권한은 600으로 하고, 저장소에는 넣지 않는다.
  - DB 주소, Redis, 프런트 주소, OAuth 복귀 주소, CORS는 `FLOWMAT_DOMAIN`에서 `compose.yaml`이 만든다.

## 2. 묶음 만들기 (클라우드 세션 또는 CI)

[점검표](../status/RELEASE-CHECKLIST.md)를 통과한 커밋에서 만든다.

```bash
deploy/pilot/package.sh
```

- **결과:** `flowmat_backend/build/pilot/flowmat-pilot-<커밋>.tar.gz`와 그 sha256이 생긴다.
- **들어 있는 것:**
  - `deploy/pilot/`: compose, Caddyfile, env.example, `release/app.jar`, `release/web/`, `release/COMMIT`
  - `scripts/ops/`: 매일 백업과 지문 SQL
- **거절:** 커밋하지 않은 변경이 있으면 만들지 않는다.

서버에서는 빌드하지 않는다. 묶음을 `scp`로 옮기고 sha256을 맞춰 본다.

## 3. VM 준비 (사람, 한 번)

1. **VM:** Ubuntu LTS, 메모리 4 GB(2 GB면 `.env`에 `APP_JAVA_OPTS=-Xms256m -Xmx768m`).
2. **접속:** SSH 키로만 들어가고 비밀번호 로그인은 끈다.
3. **방화벽:** 22(가능하면 관리하는 곳의 IP만), 80, 443만 연다.
4. **보안 업데이트:** OS의 자동 보안 업데이트(`unattended-upgrades`)를 켠다.
5. **Docker:** Docker Engine과 Compose 플러그인을 설치한다.
6. **도메인:** A 레코드가 이 VM을 가리키게 한다. 처음 띄우기 전에 해야 Caddy가 인증서를 받는다.
7. **묶음 풀기:**

```bash
sudo mkdir -p /opt/flowmat && sudo tar -xzf flowmat-pilot-<커밋>.tar.gz -C /opt/flowmat
```

```bash
cd /opt/flowmat/flowmat-pilot/deploy/pilot && cp env.example .env && chmod 600 .env
```

```bash
mkdir -p uploads && sudo chown 1000:1000 uploads
```

8. **설정 채우기:** `.env`를 채운다.
   - 비밀번호와 `JWT_SECRET`은 `openssl rand -base64 36`·`48`로 만든다.
   - 메일은 발송 서비스의 SMTP 값을 넣는다.
   - AI 초안과 개인정보 정리는 꺼진 채로 둔다(기본값).

## 4. 처음 띄우기

```bash
docker compose up -d
```

`docker compose ps`에서 `app`이 `healthy`가 되면(약 1분) 다음을 확인한다.

| 확인 | 기대 |
|---|---|
| `https://<도메인>/` | 로그인 화면 |
| `http://<도메인>/` | `https`로 308 |
| 데모 계정(`demo-owner`) 로그인 | 401 "incorrect" |
| `docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from users"'` | 0 |
| `https://<도메인>/api/actuator/health` | 404. actuator는 바깥에 없다 |
| `docker compose ps`의 postgres·redis | 호스트 포트 없음 |
| 첫 계정 가입 | 인증 코드 메일이 온다. 메일 설정이 맞는지 이것으로 본다 |
| [로그인 보안](login-security.md) §4 | 그 배포에서 확인한다 |

## 5. 매일 백업

처음 한 번 `sudo mkdir -p /opt/flowmat/backups`를 한다. 그다음 Docker를 부를 수 있는 root의 `sudo crontab -e`에 한 줄을 넣는다(매일 03:17 UTC).

```bash
17 3 * * * cd /opt/flowmat/flowmat-pilot && SOURCE_CONTAINER=flowmat-pilot-postgres-1 ENV_FILE=deploy/pilot/.env BACKUP_DIR=/opt/flowmat/backups ATTACHMENTS_DIR=deploy/pilot/uploads scripts/ops/daily-backup.sh >> /opt/flowmat/backups/cron.log 2>&1
```

- **하는 일:** 덤프를 빈 PostgreSQL에 복원해 확인하고, 첨부를 같이 묶고, 14벌을 남긴다([백업](backup-restore.md) §7).
- **기계 밖 복사(아직 없음):** 백업은 기계 밖에도 있어야 한다. 저장소는 승인 뒤에 정하고([배포안](pilot-deployment-options.md) §4), 올리기 전에 암호화한다.
- **복원 리허설:** 한 달에 한 번 그날의 덤프를 클라우드 세션으로 가져와 `restore-rehearsal.sh`로 한다.

## 6. 새 판 올리기

새 마이그레이션이 있는 판은 [§5 절차](backup-restore.md)를 먼저 한다.

1. **리허설:** 그날의 덤프를 클라우드 세션으로 가져와 `DUMP=… scripts/ops/upgrade-rehearsal.sh`를 새 커밋으로 돌린다. 통과해야 올린다.
2. **백업:** 서버에서 올리기 직전에 백업을 한 번 더 뜬다(§5의 명령을 손으로).
3. **올리기:** 새 묶음을 같은 자리에 풀고 앱을 다시 띄운다.
   - `.env`, `uploads/`, Docker 볼륨은 묶음에 없으므로 그대로 남는다.
   - 띄울 때 Flyway가 새 마이그레이션을 적용한다.

```bash
sudo tar -xzf flowmat-pilot-<새 커밋>.tar.gz -C /opt/flowmat && cd /opt/flowmat/flowmat-pilot/deploy/pilot && docker compose up -d
```

4. **되돌리기:** 마이그레이션은 앞으로만 간다. 앞 판으로 돌아가려면 2번의 백업을 복원하고 앞 묶음을 다시 푼다. 그 사이에 들어온 자료는 잃는다.

## 7. 리허설 (2026-11-28)

이 세션의 버리는 컨테이너에서 했다.

- **묶음:** `main`의 `d545c3e`에 이 묶음의 첫 판을 더한 커밋이다.
- **설정:** `FLOWMAT_DOMAIN=localhost`라 Caddy가 자체 인증서를 썼다. 메일 서버는 없는 주소였다.

| 확인 | 결과 |
|---|---|
| 처음 띄우기 | 마이그레이션 142개 적용, 앱 기동 16초, `healthy` |
| `/`, `/projects/abc` | 200, 로그인 화면(`index.html`), `Cache-Control: no-cache` |
| `/assets/*.js` | 200, `immutable` |
| 보안 헤더 | HSTS, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` |
| `http://` | 308 → `https://` |
| `/api/projects`(로그인 없이) | 401 |
| `/api/actuator/health` | 404. `/actuator/health`는 로그인 화면이다(actuator는 바깥에 없음) |
| 데모 계정 로그인 | 401, `users` 0행 |
| 협업 웹소켓 `/api/ws/websocket` | 101 |
| 앱 재시작 | `healthy`, `Schema "public" is up to date` |
| 매일 백업 | 복원 확인 통과(`migrations=142\|142`), 첨부 묶음 |
| 브라우저(Playwright) | 로그인 화면, 데모 로그인 거절 문구, 콘솔 오류 0 |

리허설에서 고친 것:

- **헬스체크:** 컨테이너 헬스체크를 전체 health에서 readiness로 바꿨다. 전체 health는 메일 서버까지 세서, 메일이 닿지 않으면 앱이 멀쩡해도 503이었다.
- **데모 계정 안내:** 운영 화면에도 로그인 아래에 데모 계정 안내(`demo-owner / demo1234`)가 보였다. 운영에는 그 계정이 없다. #196에서 운영 빌드에서는 빼도록 고쳤다.
