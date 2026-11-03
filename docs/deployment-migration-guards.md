# 배포 마이그레이션 검증

CI와 배포는 서로 다른 사실을 확인한다. PR CI는 운영 DB에 연결하지 않는다.

## CI: 이전 배포에서 업그레이드

`migration-checks.yml`은 `Deploy to EC2`의 성공 실행 중 `verified-deployment`
아티팩트가 있는 최신 실행을 찾는다. JSON의 커밋과 Actions 실행 커밋을 비교하고,
해당 커밋의 마이그레이션을 임시 PostgreSQL에 적용한 뒤 후보 버전으로 업그레이드한다.
기존 버전의 수정·삭제·번호 재사용, 신규 SQL 적용 실패를 차단한다.

아직 검증 아티팩트가 없거나 90일 보존 기간이 지났으면
`.github/deployment-baseline.sha`를 사용한다. 초기 값은 PR #34에 운영 적용을
기록한 `ee5cf503106c9cea11c730857b97c3baa034dddc`다. 이는 main이나 최신 Actions
성공 표시를 운영 버전으로 가정하지 않기 위한 명시적인 초기 기준이다.
이 초기 기준은 PR #36의 V25 복원/V26 분리를 요구한다. PR #35 그대로는 검증에 실패해야 한다.
배포 확인 후 이 기준 커밋도 주기적으로 갱신한다.

GitHub branch protection의 필수 검사에는 `PostgreSQL upgrade from deployed migrations`도
등록해야 PR 병합 자체를 막을 수 있다. 저장소 관리 설정과 별개로 배포 job의 `needs`에는
이미 연결되어 있어 검사가 실패하면 EC2 접속·컨테이너 교체를 진행하지 않는다.

## 배포 직전: 현재 DB 이력 읽기

기존 SSH 경로에서 후보 이미지의 `MigrationCheck`를 독립 실행한다.
Spring 컨텍스트, 스케줄러, SQS 소비자, HTTP 서버는 시작하지 않는다.
후보 이미지에 포함된 Flyway와 SQL로 운영 `flyway_schema_history`를 검증한다.
PostgreSQL 세션은 `default_transaction_read_only=on`이며 `migrate`, `repair`,
`clean`, `baseline`은 호출하지 않는다. 신규 pending 버전만 허용하며, missing/future,
checksum 불일치는 실패한다. 비어 있는 DB는 명시적인 최초 구축이 필요하므로 실패한다.
검증 실패 시 기존 서버를 중지하지 않는다.
검증 프로세스는 DB 연결 지연을 포함해 120초로 제한하고 시간 초과도 실패로 처리한다.

지금 애플리케이션과 동일한 `SPRING_DATASOURCE_*`, 기본 schema/history table을 사용한다.
별도 schema/table/location을 도입하면 애플리케이션과 checker를 함께 변경해야 한다.
Actions runner에 DB 비밀번호를 전달하거나 운영 DB 포트를 외부로 열 필요는 없다.
DB 연결은 EC2 안에서 검증할 때만 사용한다.

## 배포 완료와 롤백

후보 컨테이너가 동일 이미지로 재시작 없이 실행 중이고,
`/actuator/health/readiness`가 연속 3회 `UP`이어야 한다. readiness에는 DB 연결 확인을 포함한다.
기본 대기 한도는 180초다. 뒤이어 pending도 허용하지 않는 이력 검증을 수행하고
실제 적용된 버전·스크립트·체크섬·커밋·확인 시각을 `migration-state.json`으로 저장한다.
관리 포트는 호스트 loopback에만 게시한다.

기동/헬스/최종 검증 실패 시 이전 컨테이너를 복원하고 `/actuator/health`로 확인한다.
복원이 성공해도 배포 실행은 실패로 남긴다. DB 변경을 되돌리지는 않는다.
새 마이그레이션은 이전 앱과 호환되는 확장부터 적용해야 하며 파괴적 변경에는 별도 배포 절차가 필요하다.
아티팩트 업로드만 실패한 경우 이미 정상인 서버를 다시 내리지는 않는다.

## 서버 접속 없이 확인할 수 있는 정보

GitHub Actions → 성공한 `Deploy to EC2` 실행 → Summary 또는
`verified-deployment` 아티팩트의 `migration-state.json`에서 **검증 시점**의
배포 커밋과 적용 이력을 볼 수 있다. JSON에는 접속 주소·비밀번호·업무 데이터가 없다.
EC2에도 `REMOTE_DEPLOY_DIR/verified-<SHA>/migration-state.json`이 남는다.

이는 스냅샷이다. 이후 수동 DB 변경, 다른 경로의 배포, 실패한 마이그레이션으로 생긴
현재 상태는 알 수 없다. 따라서 CI 통과와 관계없이 배포 직전 live 검증을 생략하지 않는다.

참고: [Flyway validate](https://documentation.red-gate.com/flyway/reference/commands/validate),
[Spring Boot readiness](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).
