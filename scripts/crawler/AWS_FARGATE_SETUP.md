# Inning Log 크롤러 AWS Fargate 설정 안내서

확인일: **2026-08-26**

대상 리전: **Asia Pacific (Seoul), `ap-northeast-2`**

대상 워크로드: [TARGET_SCOPE.md](./TARGET_SCOPE.md)에 정의한 KBO 일정·스코어보드 수집기

이 문서는 AWS를 처음 설정하는 팀원이 콘솔에서 어떤 메뉴와 버튼을 눌러야 하는지 설명한다. AWS 콘솔 문구는 계정 언어와 UI 업데이트에 따라 조금 달라질 수 있으므로 괄호 안의 영문 서비스명과 리소스 이름을 함께 확인한다.

> **2026-09-14 실행 업데이트:** 실제 수집과 검증은 [LIVE_RUN.md](./LIVE_RUN.md)를 먼저 따른다. `kbo-live`는 운영자 요청 실행, `kbo-locked`는 기존 승인 확인 모드다. 아래의 서면 승인 관련 절차는 `kbo-locked`에 해당한다. 일정 화면의 공개 AJAX 요청도 수집 범위에 포함된다.

## 1. 완성될 구조

```text
EventBridge Scheduler
  ├─ 매일 06:00 KST: plan-day
  │                     │
  │                     └─ KBO 일정 확인 후 일회성 schedule 생성
  │
  └─ 경기 60분 전: run-game-window
                        │
                        ├─ DynamoDB: 상태·lease·요청량
                        ├─ SQS: 정규화 GameSnapshot
                        └─ CloudWatch Logs: 운영 로그
```

ECS **Service**는 만들지 않는다. 항상 떠 있는 서버가 아니라 필요할 때 시작하고 종료하는 ECS **Task**만 사용한다. Application Load Balancer, NAT Gateway, Elastic IP도 1차 구성에는 만들지 않는다.

## 2. 고정할 리소스 이름

문서와 실제 리소스 이름을 맞추면 IAM과 장애 대응이 단순해진다.

| 구분 | 값 |
| --- | --- |
| AWS Region | `ap-northeast-2` |
| ECR repository | `inning-log/crawler` |
| ECS cluster | `inning-log-crawler` |
| Task definition family | `inning-log-crawler` |
| Container name | `crawler` |
| CloudWatch log group | `/inning-log/crawler` |
| DynamoDB table | `inning-log-crawler-state` |
| 결과 SQS | `inning-log-game-snapshots` |
| 결과 DLQ | `inning-log-game-snapshots-dlq` |
| Scheduler DLQ | `inning-log-crawler-scheduler-dlq` |
| Security group | `inning-log-crawler-egress` |
| ECS execution role | `inning-log-crawler-task-execution` |
| 애플리케이션 task role | `inning-log-crawler-task` |
| Scheduler execution role | `inning-log-crawler-scheduler` |
| Schedule group | `inning-log-crawler` |
| 일일 계획 schedule | `inning-log-crawler-plan-daily` |

모든 리소스에는 가능하면 다음 태그를 붙인다.

| Key | Value |
| --- | --- |
| `Project` | `InningLog` |
| `Component` | `Crawler` |
| `Environment` | `dev` 또는 `prod` |
| `ManagedBy` | 초기에는 `Console`, IaC 전환 후 `Terraform` 또는 선택한 도구 |

## 3. AWS 생성 전에 끝내야 하는 코드 작업

Fargate 운영 경로는 `src/fargate/`와 `config/fargate.yml`로 격리했다. 과거 범용 adapter가 저장소에 남아 있어도 Docker 기본 명령과 Scheduler target은 `src/fargate/main.js`만 실행한다. 별도 repository 이전은 배포 경계를 더 명확하게 만들기 위한 후속 선택 사항이며 현재 코드의 선행 조건은 아니다.

- [x] 허용 URL이 `Schedule.aspx`, `ScoreBoard.aspx` 두 문자열로 고정되어 환경변수로 바뀌지 않는다.
- [x] Fargate 운영 profile과 네트워크 source에는 NAVER와 KBO 내부 ASMX/JSON endpoint가 없다.
- [x] `plan-day`와 `run-game-window` 실행 모드가 구현되어 있다.
- [x] DynamoDB 상태·조건부 lease·시간당 지속 요청량·circuit adapter가 구현되어 있다.
- [x] SQS `GameSnapshot` publisher와 내용 지문 기반 idempotency key가 구현되어 있다.
- [x] EventBridge Scheduler의 일회성 ECS Task create/update/delete가 구현되어 있다.
- [x] Dockerfile과 `.dockerignore`가 있고 이미지 기본 명령은 fixture dry-run이다.
- [x] 정책 검사와 주입형 테스트가 허가 전 실 네트워크를 차단한다.
- [x] KBO 서면 허가 없이는 `kbo-locked`가 disabled, unverified, kill switch 상태다.
- [ ] 이 변경을 GitHub에 push한 뒤 실제 Docker build와 fixture 컨테이너 검사를 완료한다.

AWS 리소스는 먼저 만들어도 되지만, fixture 외 실행을 켜서는 안 된다.

## 4. 0단계 — 리전과 비용 알림

### 4.1 리전 고정

1. [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 화면 오른쪽 위의 리전 이름을 누른다.
3. **Asia Pacific (Seoul)**을 선택한다.
4. 이후 화면 오른쪽 위가 `Seoul`인지 매 단계 확인한다.

ECR, ECS, DynamoDB, SQS, CloudWatch Logs와 EventBridge Scheduler는 모두 같은 리전에 만든다.

### 4.2 월 비용 Budget 생성

1. 상단 검색창에 `Billing and Cost Management`를 입력해 연다.
2. 왼쪽 메뉴에서 **Budgets**를 누른다.
3. **Create budget**을 누른다.
4. **Customize (advanced)**를 선택한다.
5. **Cost budget**을 선택하고 **Next**를 누른다.
6. Budget name은 `inning-log-crawler-monthly`로 입력한다.
7. Period는 **Monthly**, Budgeting method는 **Fixed**로 선택한다.
8. 팀이 허용할 월 금액을 입력한다. 초기 dev 예시는 `10 USD`다.
9. 알림은 최소 50%, 80%, 100% Actual과 100% Forecasted를 등록한다.
10. 팀 공용 이메일을 입력하고 생성한다.

Budget 알림은 실시간 강제 차단 장치가 아니며 청구 데이터 반영이 늦을 수 있다. 크롤러의 하드 타임아웃과 Scheduler 비활성화 절차를 별도로 유지한다. AWS의 현재 콘솔 순서는 [공식 Budget 생성 안내](https://docs.aws.amazon.com/cost-management/latest/userguide/create-cost-budget.html)를 참고한다.

## 5. 1단계 — 크롤러 이미지를 ECR에 올리기

ECR은 크롤러 Docker 이미지를 저장한다. 이 단계에서도 EC2는 만들지 않는다.

| 작업 | 실행 위치 |
| --- | --- |
| ECR repository 생성·이미지 확인 | AWS Management Console |
| 소스 clone, Docker build, ECR push | AWS CloudShell |
| ECR 이미지 실행 | 이후 생성할 ECS Fargate Task |

CloudShell은 이미지를 준비하는 브라우저 터미널일 뿐, 계속 실행되는 크롤링 서버가 아니다. 이미지를 ECR에 올리고 나면 닫아도 된다.

### 5.1 ECR repository 생성

1. 상단 검색창에서 `Elastic Container Registry` 또는 `ECR`을 연다.
2. 왼쪽에서 **Private repositories**를 선택한다.
3. **Create repository**를 누른다.
4. Visibility는 **Private**로 둔다.
5. Repository name에 `inning-log/crawler`를 입력한다.
6. Tag mutability는 **Immutable**을 선택한다.
7. 바로 아래 **변경할 수 없는 태그 제외(Immutable tag exclusions)**에는 아무것도 입력하지 않는다. 필터를 추가했다면 해당 행의 삭제 버튼으로 제거하여 `0개`로 둔다.
   - 이 입력칸은 이미지 태그를 만드는 곳이 아니라, Immutable 규칙에서 예외로 두어 **덮어쓰기를 허용할 태그**를 지정하는 곳이다.
   - `latest`를 넣으면 `latest`만, `dev-*`를 넣으면 일치하는 태그를 덮어쓸 수 있다.
   - `*`를 넣으면 사실상 모든 태그가 덮어쓰기 가능해지므로 입력하지 않는다.
   - Git commit SHA도 이 입력칸에 넣지 않는다.
8. Encryption은 기본 AWS managed encryption을 사용한다.
9. **Create repository**를 누른다.

repository 상세의 **View push commands**는 계정 환경에 따라 PowerShell 명령과 `latest` 태그를 보여 줄 수 있다. 이 프로젝트에서는 그 명령을 그대로 복사하지 않고 5.5절의 CloudShell 명령을 사용한다.

자세한 동작은 [ECR 이미지 태그 불변성](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-tag-mutability.html), 생성 절차는 [ECR repository 생성](https://docs.aws.amazon.com/AmazonECR/latest/userguide/repository-create.html)을 참고한다.

### 5.2 현재 진행 체크포인트

2026-08-26에 다음 단계까지 확인했다.

- [x] Seoul 리전에서 `inning-log/crawler` private repository를 생성했다.
- [x] Tag mutability를 `Immutable`, 제외 필터를 `0개`로 설정했다.
- [x] CloudShell에서 `https://github.com/Inning-Log/Back.git` clone에 성공했다.
- [x] CloudShell에서 ECR의 `Login Succeeded`를 확인했다.
- [x] clone한 commit `c7b0f7c2d765`에는 `scripts/crawler/Dockerfile`이 없어 build가 실패했고, 이 실행에서는 ECR에 이미지가 올라가지 않았음을 확인했다.
- [ ] 크롤러 Dockerfile과 Fargate 실행 코드가 포함된 새 commit을 GitHub에 push한다.
- [ ] CloudShell에서 새 commit을 pull한 뒤 build·fixture 검증·push를 다시 실행한다.
- [ ] ECR Images 화면에서 새 commit SHA 태그와 digest를 확인한다.

당시 출력은 다음 의미다.

| 출력 | 판정 |
| --- | --- |
| `Login Succeeded` | CloudShell의 ECR 인증만 성공 |
| `open Dockerfile: no such file or directory` | Docker 이미지 build 실패 |
| `No such image` | build 실패로 tag할 로컬 이미지가 없음 |
| `An image does not exist locally` | push할 이미지가 없어 ECR 업로드 실패 |

따라서 현재 체크포인트의 다음 행동은 **Dockerfile이 포함된 commit을 GitHub에 먼저 push하는 것**이다. ECR push가 끝난 것으로 간주하거나 `latest` 명령으로 우회하지 않는다.

### 5.3 CloudShell 재시도 전 코드 체크

이 절차는 코드 작업 담당자가 로컬 저장소에서 수행한다. 다음 파일이 모두 Git commit에 포함되어 GitHub에서 보이는 상태여야 한다.

```text
scripts/crawler/Dockerfile
scripts/crawler/.dockerignore
scripts/crawler/package.json
scripts/crawler/package-lock.json
scripts/crawler/src/fargate/main.js
scripts/crawler/config/fargate.yml
scripts/crawler/fixtures/kbo/
```

저장소 루트에서 테스트한다.

```powershell
npm --prefix scripts/crawler ci
npm --prefix scripts/crawler test
docker build --tag inning-log/crawler:local-check scripts/crawler
docker run --rm inning-log/crawler:local-check --check-config --profile fixture
docker run --rm inning-log/crawler:local-check --plan-day --profile fixture --dry-run
```

성공 조건은 다음과 같다.

- 전체 Node 테스트가 통과한다.
- Docker build가 exit code `0`으로 끝난다.
- config 검사 결과에 `"ok":true`, `"profile":"fixture"`가 나온다.
- dry-run 결과에 `"dryRun":true`, `"externalRequests":0`이 나온다.
- Dockerfile이 있는 commit을 GitHub에 push했다.

로컬 PC에 Docker가 없다면 Node 테스트와 GitHub push를 먼저 완료하고, Docker build와 두 fixture 검사는 5.6절의 CloudShell에서 수행해도 된다.

### 5.4 CloudShell에서 GitHub 최신 commit 받기

1. AWS 콘솔 상단의 `>_` 모양 **CloudShell**을 연다.
2. 아래 코드 블록 안의 명령만 입력한다. 프롬프트에 표시되는 `$`, `crawler $`, 줄 번호와 Markdown의 백틱은 입력하지 않는다.
3. 이미 clone한 `Back` 저장소로 이동하고, Dockerfile이 push된 브랜치를 명시적으로 받는다.

```bash
cd ~/Back
git status --short
git fetch origin
git switch feat/fcm-notifications
git pull --ff-only origin feat/fcm-notifications
git branch --show-current
git log -1 --oneline
test -f scripts/crawler/Dockerfile && echo "OK: crawler Dockerfile exists"
cd scripts/crawler
```

다음을 모두 만족해야 5.5절로 간다.

- `git branch --show-current`가 `feat/fcm-notifications`를 표시한다.
- `git log -1 --oneline`이 최소한 Dockerfile 추가 commit `e807e4c`를 포함하거나 그보다 나중 commit을 표시한다.
- `git status --short`에 CloudShell에서 만든 임의 변경이 나오지 않는다.
- 마지막 검사에서 `OK: crawler Dockerfile exists`가 출력된다.

`git switch`나 `git pull`이 충돌하면 `reset --hard`로 지우지 말고 작업을 멈추고 CloudShell 변경 내용을 먼저 확인한다. `OK`가 출력되지 않으면 build나 tag/push를 시도하지 말고 위 브랜치와 commit 출력부터 다시 확인한다.

### 5.5 ECR 변수 설정과 로그인

CloudShell은 Bash를 사용한다. 다음 블록을 그대로 실행한다. AWS 계정 ID는 현재 로그인한 계정에서 자동으로 읽으므로 문서에 하드코딩하지 않는다.

```bash
ECR_REGION=ap-northeast-2
ECR_REPOSITORY=inning-log/crawler
ECR_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="${ECR_ACCOUNT_ID}.dkr.ecr.${ECR_REGION}.amazonaws.com"
IMAGE_TAG=$(git -C ../.. rev-parse --short=12 HEAD)
IMAGE_URI="${ECR_REGISTRY}/${ECR_REPOSITORY}:${IMAGE_TAG}"

printf 'ECR repository: %s\nGit SHA tag: %s\nImage URI: %s\n' "$ECR_REPOSITORY" "$IMAGE_TAG" "$IMAGE_URI"
aws ecr describe-repositories --region "$ECR_REGION" --repository-names "$ECR_REPOSITORY"
aws ecr get-login-password --region "$ECR_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"
```

성공 조건은 다음 두 가지다.

- `describe-repositories` 결과의 repository name이 `inning-log/crawler`다.
- 마지막 줄에 `Login Succeeded`가 나온다.

Docker 설정 파일에 인증 정보가 암호화되지 않은 채 저장된다는 경고는 로그인 실패가 아니다. push를 마친 뒤 5.7절에서 logout한다. `aws sts get-caller-identity`의 Account가 의도한 AWS 계정인지도 확인한다.

### 5.6 Docker build, fixture 검증, ECR push

5.5절과 같은 CloudShell 세션, `~/Back/scripts/crawler` 디렉터리에서 순서대로 실행한다. 한 명령이 실패하면 아래 명령을 계속 실행하지 말고 해당 오류부터 해결한다.

```bash
docker build --tag "$ECR_REPOSITORY:$IMAGE_TAG" .
```

build 마지막에 `FINISHED`가 나오고 오류가 없어야 한다. 이어서 컨테이너가 외부 요청 없이 실행되는지 확인한다.

```bash
docker run --rm "$ECR_REPOSITORY:$IMAGE_TAG" --check-config --profile fixture
docker run --rm "$ECR_REPOSITORY:$IMAGE_TAG" --plan-day --profile fixture --dry-run
```

첫 번째 결과에는 `"ok":true`, 두 번째 결과에는 `"externalRequests":0`이 있어야 한다. 두 명령이 모두 성공한 경우에만 tag와 push를 실행한다.

```bash
docker tag "$ECR_REPOSITORY:$IMAGE_TAG" "$IMAGE_URI"
docker push "$IMAGE_URI"
```

push 마지막에 다음과 같은 digest가 출력되어야 한다.

```text
<git-sha>: digest: sha256:<digest> size: <number>
```

AWS가 보여 준 `latest` 명령과 오타인 `lates` 명령은 사용하지 않는다. Git commit SHA는 repository 생성 화면에서 고르는 옵션이 아니라, 위 `IMAGE_TAG` 변수로 push 시점에 붙이는 불변 이미지 버전이다.

### 5.7 ECR 업로드 검증과 기록

CloudShell에서 AWS API로 실제 업로드 결과를 확인한다.

```bash
aws ecr describe-images \
  --region "$ECR_REGION" \
  --repository-name "$ECR_REPOSITORY" \
  --image-ids imageTag="$IMAGE_TAG" \
  --query 'imageDetails[0].{tags:imageTags,digest:imageDigest,pushedAt:imagePushedAt}' \
  --output table

printf 'Task definition image URI: %s\n' "$IMAGE_URI"
docker logout "$ECR_REGISTRY"
```

AWS 콘솔에서도 교차 확인한다.

1. `ECR → Private repositories → inning-log/crawler`를 연다.
2. **Images** 목록에서 `IMAGE_TAG`와 같은 12자리 Git SHA 태그를 찾는다.
3. 이미지 digest가 CloudShell의 `docker push` 결과와 같은지 확인한다.
4. `IMAGE_URI` 전체를 별도로 기록한다. 12절 Task definition의 Image URI에 그대로 사용한다.

다음 체크를 모두 완료해야 ECR 단계를 끝낸다.

- [ ] Docker build 성공
- [ ] fixture config 검사 성공
- [ ] fixture dry-run의 외부 요청 `0` 확인
- [ ] Docker push에서 digest 확인
- [ ] ECR Images에서 Git SHA 태그 확인
- [ ] Task definition에 사용할 전체 `IMAGE_URI` 기록

완료한 뒤에만 6절 CloudWatch 로그 그룹 생성으로 진행한다.

### 5.8 ECR 단계 오류 해석

| 출력 | 원인과 조치 |
| --- | --- |
| `bash: $'\r': command not found` | Windows 줄바꿈 문자가 붙은 것이다. 보통 빈 줄에서 한 번 나온 것은 무해하다. 코드 블록의 명령만 다시 붙여 넣고 `$` 프롬프트 문자는 입력하지 않는다. |
| `open Dockerfile: no such file or directory` | 현재 디렉터리 또는 GitHub commit에 crawler Dockerfile이 없다. `pwd`, `git log -1 --oneline`, `test -f Dockerfile`을 확인한다. |
| `No such image` | 앞선 Docker build가 실패했다. tag나 push를 반복하지 말고 build 오류를 먼저 해결한다. |
| `An image does not exist locally` | 로컬 tag가 만들어지지 않았다. build와 tag의 성공 여부 및 `IMAGE_TAG` 값을 확인한다. |
| `ImageTagAlreadyExistsException` | 같은 Git SHA 태그가 이미 존재하고 Immutable이 덮어쓰기를 막았다. ECR에서 기존 digest를 확인하고 같은 태그를 덮어쓰지 않는다. |
| `AccessDeniedException` | 현재 CloudShell IAM 주체에 필요한 ECR 권한이 없다. 로그인한 계정과 `ecr:GetAuthorizationToken`, push 권한을 확인한다. |
| `Login Succeeded` 뒤 build 실패 | 로그인 성공은 이미지 생성·업로드 성공을 뜻하지 않는다. build, fixture 검사, push와 ECR 조회를 각각 확인한다. |

## 6. 2단계 — CloudWatch 로그 그룹 생성

1. 상단 검색창에서 `CloudWatch`를 연다.
2. 왼쪽 메뉴에서 **Log Management → Log groups**를 선택한다.
3. **Create log group**을 누른다.
4. Log group name에 `/inning-log/crawler`를 입력한다.
5. Retention은 dev `14 days`, 운영 `30 days`를 권장한다.
6. **Create**를 누른다.

기본값인 `Never expire`를 그대로 두지 않는다. 로그에는 응답 원문, HTML, 쿠키, 허가 문서 내용과 개인정보를 남기지 않는다. [CloudWatch log group 생성·보존 설정](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/Working-with-log-groups-and-streams.html)

## 7. 3단계 — SQS와 DLQ 생성

결과 전달 queue와 Scheduler 실패 queue를 분리한다.

### 7.1 결과 DLQ

1. 상단 검색창에서 `Simple Queue Service` 또는 `SQS`를 연다.
2. **Create queue**를 누른다.
3. Type은 **Standard**를 선택한다.
4. Name에 `inning-log-game-snapshots-dlq`를 입력한다.
5. Message retention period는 `14 days`로 설정한다.
6. Server-side encryption은 활성 상태로 유지한다.
7. **Create queue**를 누른다.

### 7.2 결과 queue

1. 다시 **Create queue**를 누른다.
2. Type은 **Standard**로 선택한다.
3. Name은 `inning-log-game-snapshots`다.
4. Visibility timeout은 백엔드 처리 제한보다 길게 둔다. 초기값은 `60 seconds`다.
5. Message retention period는 `4 days`로 둔다.
6. **Dead-letter queue**를 펼쳐 활성화한다.
7. DLQ로 `inning-log-game-snapshots-dlq`를 선택한다.
8. Maximum receives는 `5`로 입력한다.
9. **Create queue**를 누른다.
10. 생성 후 Queue URL과 ARN은 7.4절에서 세 queue를 한꺼번에 확인하고 기록한다.

> **지금 AWS 콘솔에서 할 작업은 아님:** Standard queue는 메시지가 중복 전달될 수 있으므로 결과를 소비하는 백엔드는 나중에 `source + externalGameId + contentFingerprint`를 고유 처리 키로 사용해 멱등 upsert해야 한다. 이것은 SQS 생성 화면의 옵션이 아니라 백엔드 구현 요구사항이다.

### 7.3 Scheduler DLQ

같은 방식으로 `inning-log-crawler-scheduler-dlq`라는 Standard queue를 만든다.

- Message retention: `14 days`
- Encryption: 활성
- 애플리케이션 메시지를 보내는 queue가 아니라 Scheduler가 ECS Task 호출에 실패했을 때 사용하는 queue다.

문서 순서대로 결과 DLQ와 결과 queue 두 개를 만들었다면, 현재 다음 행동은 이 세 번째 queue를 만드는 것이다. 결과 queue처럼 별도의 DLQ를 다시 연결하지 않는다.

### 7.4 Queue URL과 ARN 확인·기록

총 세 개의 queue가 있어야 한다.

| Queue name | 용도 | 반드시 기록할 값 |
| --- | --- | --- |
| `inning-log-game-snapshots` | 크롤러 결과 전달 | Queue URL, ARN |
| `inning-log-game-snapshots-dlq` | 결과 처리 반복 실패 보관 | ARN |
| `inning-log-crawler-scheduler-dlq` | Scheduler의 ECS 호출 실패 보관 | ARN |

콘솔에서는 다음 순서로 찾는다.

1. `SQS → Queues` 목록으로 이동한다.
2. 왼쪽 체크박스만 선택하지 말고 **queue 이름 링크 자체**를 누른다.
3. 열린 queue 상세 화면의 위쪽 요약 영역에서 **URL**과 **ARN**을 찾는다. 화면 폭에 따라 `Details`가 탭이 아니라 접힌 요약 영역으로 보일 수 있다.
4. 각 값 오른쪽의 복사 아이콘으로 기록한다.
5. 뒤로 가서 나머지 queue도 같은 방식으로 확인한다.

콘솔에서 항목을 찾기 어렵다면 CloudShell에서 다음 블록으로 세 queue를 한 번에 조회한다. 이 방법으로 나온 값이 공식 API가 반환한 실제 식별자다.

```bash
SQS_REGION=ap-northeast-2
SNAPSHOT_QUEUE_NAME=inning-log-game-snapshots
SNAPSHOT_DLQ_NAME=inning-log-game-snapshots-dlq
SCHEDULER_DLQ_NAME=inning-log-crawler-scheduler-dlq

SNAPSHOT_QUEUE_URL=$(aws sqs get-queue-url --region "$SQS_REGION" --queue-name "$SNAPSHOT_QUEUE_NAME" --query QueueUrl --output text)
SNAPSHOT_DLQ_URL=$(aws sqs get-queue-url --region "$SQS_REGION" --queue-name "$SNAPSHOT_DLQ_NAME" --query QueueUrl --output text)
SCHEDULER_DLQ_URL=$(aws sqs get-queue-url --region "$SQS_REGION" --queue-name "$SCHEDULER_DLQ_NAME" --query QueueUrl --output text)

SNAPSHOT_QUEUE_ARN=$(aws sqs get-queue-attributes --region "$SQS_REGION" --queue-url "$SNAPSHOT_QUEUE_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
SNAPSHOT_DLQ_ARN=$(aws sqs get-queue-attributes --region "$SQS_REGION" --queue-url "$SNAPSHOT_DLQ_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
SCHEDULER_DLQ_ARN=$(aws sqs get-queue-attributes --region "$SQS_REGION" --queue-url "$SCHEDULER_DLQ_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

printf 'Snapshot Queue URL: %s\nSnapshot Queue ARN: %s\nSnapshot DLQ ARN: %s\nScheduler DLQ ARN: %s\n' \
  "$SNAPSHOT_QUEUE_URL" \
  "$SNAPSHOT_QUEUE_ARN" \
  "$SNAPSHOT_DLQ_ARN" \
  "$SCHEDULER_DLQ_ARN"
```

마지막으로 결과 queue에 DLQ가 정확히 연결됐는지 확인한다.

```bash
aws sqs get-queue-attributes \
  --region "$SQS_REGION" \
  --queue-url "$SNAPSHOT_QUEUE_URL" \
  --attribute-names RedrivePolicy \
  --query 'Attributes.RedrivePolicy' \
  --output text
```

정상이면 한 줄 JSON 안에 다음 두 값이 표시된다.

```text
"deadLetterTargetArn":"arn:aws:sqs:ap-northeast-2:<account-id>:inning-log-game-snapshots-dlq"
"maxReceiveCount":"5"
```

`None`, `null` 또는 빈 출력이면 결과 queue에 DLQ가 연결되지 않은 것이다. `SQS → inning-log-game-snapshots → Edit → Dead-letter queue`에서 `inning-log-game-snapshots-dlq`, Maximum receives `5`를 설정하고 저장한다.

출력값은 비밀번호가 아니지만 계정별 리소스 식별자이므로 임의로 줄이거나 이름만 적지 않는다. 이 저장소에서는 Git과 Docker에서 제외되는 `scripts/crawler/.env.aws.local`에 `DEPLOY_*` 이름으로 기록한다. 공개 저장소 문서나 추적되는 설정 파일에는 실제 계정 ID가 포함된 값을 넣지 않는다. 이 파일은 배포 기록 전용이며 크롤러 runtime env 파일로 로드하지 않는다.

- `SNAPSHOT_QUEUE_URL`은 12절 Task definition의 `CRAWLER_SNAPSHOT_QUEUE_URL`에 사용한다.
- `SNAPSHOT_QUEUE_ARN`은 9.2절 크롤러 task role의 `sqs:SendMessage` Resource에 사용한다.
- `SCHEDULER_DLQ_ARN`은 9.3절과 14절 EventBridge Scheduler 설정에 사용한다.
- `SNAPSHOT_DLQ_ARN`은 결과 queue의 Redrive policy가 올바른지 확인할 때 사용한다.

다음 체크를 모두 마치면 8절 DynamoDB로 진행한다.

- [ ] 세 queue가 모두 존재함
- [ ] `inning-log-game-snapshots`에 결과 DLQ와 Maximum receives `5`가 연결됨
- [ ] Snapshot Queue URL 기록
- [ ] Snapshot Queue ARN 기록
- [ ] Snapshot DLQ ARN 기록
- [ ] Scheduler DLQ ARN 기록

AWS 콘솔의 기본 생성 흐름은 [SQS Standard queue 생성](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/creating-sqs-standard-queues.html), DLQ 동작은 [SQS DLQ 설명](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)을 참고한다.

## 8. 4단계 — DynamoDB 상태 테이블 생성

Fargate Task의 메모리와 로컬 파일은 Task가 끝나면 다음 실행에서 이어서 사용할 수 없다. 이 프로젝트는 여러 실행이 공유해야 하는 잠금, 최근 수집 상태, 중복 발행 방지, 누적 요청량과 차단 상태를 DynamoDB에 둔다.

| Partition key 예시 | 용도 |
| --- | --- |
| `LEASE#PLAN#2026-08-26` | 같은 날짜의 계획 Task가 중복 실행되지 않도록 분산 잠금 |
| `LEASE#WINDOW#2026-08-26` | 같은 경기일의 실시간 수집 Task가 동시에 두 개 돌지 않도록 분산 잠금 |
| `DAY#2026-08-26` | 당일 경기 일정과 계산된 실행 시각 |
| `SCHEDULE#MONTH#2026-08` | 일정 페이지 한 번에서 정규화한 표시 월 전체 일정·결과 |
| `LATEST#2026-08-26` | 가장 최근에 정규화한 경기 snapshot |
| `PUBLISHED#2026-08-26#game-window` | 같은 내용의 SQS 메시지를 반복 발행하지 않기 위한 fingerprint |
| `QUOTA#KBO#LOGICAL#2026082609` | Task 재시작을 넘어 유지되는 시간당 논리 요청 수 |
| `QUOTA#KBO#ATTEMPT#2026082609` | retry를 포함한 실제 HTTP 시도 수 |
| `CIRCUIT#KBO` | 403·429·CAPTCHA 등으로 수집을 중단한 상태와 재개 가능 시각 |

구분은 다음과 같다.

- Docker build와 `fixture --dry-run` 검증에는 DynamoDB가 필요하지 않다. 메모리 상태 저장소만 사용한다.
- 실제 KBO `plan-day`와 `run-game-window`를 Fargate에서 실행할 때는 현재 구현상 필수다.
- DynamoDB라는 제품 자체가 이론적으로 유일한 방법은 아니지만, 다른 저장소로 바꾸려면 동일한 조건부 잠금·원자적 quota·TTL·공유 상태 기능과 코드 adapter를 별도로 구현해야 한다.
- 현재 구성에서는 별도 서버 없이 요청한 만큼만 사용하는 DynamoDB On-demand를 기준으로 하므로 DynamoDB를 그대로 사용한다.

1. 상단 검색창에서 `DynamoDB`를 연다.
2. 왼쪽 메뉴에서 **Tables**를 선택한다.
3. **Create table**을 누른다.
4. Table name에 `inning-log-crawler-state`를 입력한다.
5. Partition key는 `pk`, Type은 **String**으로 지정한다.
6. Sort key는 만들지 않는다.
7. Table settings에서 **Customize settings**를 선택한다.
8. Capacity mode는 **On-demand** 또는 **Pay per request**를 선택한다.
9. Encryption은 기본 AWS owned key를 사용한다.
10. **Create table**을 누른다.
11. Status가 `ACTIVE`가 될 때까지 기다린다.
12. 테이블 상세의 **Additional settings** 또는 **Time to Live (TTL)**에서 TTL을 켠다.
13. TTL attribute name은 코드가 실제로 기록하는 `expiresAt`으로 정확히 입력한다. `expiresAtEpoch`가 아니다.

다음은 크롤러가 실행되면서 DynamoDB API로 **자동 생성하는 항목의 `pk` 예시**다. 테이블 생성 직후 콘솔에서 수동으로 입력하거나 미리 만들지 않는다. 날짜와 시간 부분도 실행 시점에 코드가 계산한다.

```text
LEASE#PLAN#2026-08-26
LEASE#WINDOW#2026-08-26
DAY#2026-08-26
SCHEDULE#MONTH#2026-08
LATEST#2026-08-26
PUBLISHED#2026-08-26#game-window
QUOTA#KBO#LOGICAL#2026082609
QUOTA#KBO#ATTEMPT#2026082609
CIRCUIT#KBO
```

테이블 생성 직후 Items가 `0`개인 것이 정상이다. fixture dry-run은 DynamoDB를 사용하지 않으므로 항목을 만들지 않는다. 이후 AWS 설정을 연결한 실제 `plan-day` 또는 `run-game-window` Task가 실행되면 필요한 항목만 생성된다.

lease는 DynamoDB conditional write로 한 작업만 획득해야 한다. 단순 `PutItem` 후 확인하는 방식은 중복 실행을 막지 못한다. 공식 콘솔 생성 순서는 [DynamoDB table 생성](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/getting-started-step-1.html)을 참고한다.

## 9. 5단계 — IAM 역할 세 개 생성

실행 역할과 애플리케이션 역할을 섞지 않는다.

### 9.1 ECS task execution role

이 역할은 ECS가 ECR 이미지를 가져오고 CloudWatch에 로그를 쓰는 데 사용한다. 컨테이너 코드가 직접 쓰는 권한이 아니다.

1. 상단 검색창에서 `IAM`을 연다.
2. 왼쪽에서 **Roles**를 누른다.
3. **Create role**을 누른다.
4. Trusted entity type은 **AWS service**다.
5. Service or use case에서 **Elastic Container Service**를 선택한다.
6. Use case는 **Elastic Container Service Task**를 선택한다.
7. **Next**를 누른다.
8. `AmazonECSTaskExecutionRolePolicy`를 검색해 선택한다.
9. **Next**를 누른다.
10. Role name에 `inning-log-crawler-task-execution`을 입력한다.
11. **Create role**을 누른다.

이는 AWS가 안내하는 Fargate execution role 구성과 같다. [ECS task execution role](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html)

### 9.2 크롤러 application task role

1. IAM **Roles → Create role**로 다시 들어간다.
2. **AWS service → Elastic Container Service → Elastic Container Service Task**를 선택한다.
3. 권한 선택 화면에서는 광범위한 관리형 정책을 붙이지 않고 다음 단계로 이동한다.
4. Role name에 `inning-log-crawler-task`를 입력하고 생성한다.
5. 생성한 role을 열고 **Add permissions → Create inline policy**를 누른다.
6. JSON 탭에서 실제 계정 ID와 리전으로 치환한 최소 정책을 입력한다.

> 아래 `ACCOUNT_ID`는 AWS에 그대로 입력하는 값이 아니다. JSON 전체에서 총 **4곳**을 찾아 현재 계정의 12자리 숫자로 모두 바꾼다. 밑줄, `< >`, 따옴표 밖의 설명 문자는 추가하지 않는다. 계정 ID를 모르면 CloudShell에서 `aws sts get-caller-identity --query Account --output text`로 확인한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CrawlerState",
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:DescribeTable"
      ],
      "Resource": "arn:aws:dynamodb:ap-northeast-2:ACCOUNT_ID:table/inning-log-crawler-state"
    },
    {
      "Sid": "PublishSnapshots",
      "Effect": "Allow",
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:ap-northeast-2:ACCOUNT_ID:inning-log-game-snapshots"
    },
    {
      "Sid": "ManageOwnSchedules",
      "Effect": "Allow",
      "Action": [
        "scheduler:CreateSchedule",
        "scheduler:GetSchedule",
        "scheduler:UpdateSchedule",
        "scheduler:DeleteSchedule"
      ],
      "Resource": "arn:aws:scheduler:ap-northeast-2:ACCOUNT_ID:schedule/inning-log-crawler/*"
    },
    {
      "Sid": "PassOnlyCrawlerSchedulerRole",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::ACCOUNT_ID:role/inning-log-crawler-scheduler",
      "Condition": {
        "StringEquals": {
          "iam:PassedToService": "scheduler.amazonaws.com"
        }
      }
    }
  ]
}
```

붙여 넣은 뒤 JSON 검색으로 `ACCOUNT_ID`가 `0건` 남았는지 확인한다. 한 곳이라도 남으면 IAM 정책 검증이 실패한다.

Policy name은 `inning-log-crawler-runtime`으로 입력한다. `Resource: "*"`로 넓히지 않는다.

### 9.3 EventBridge Scheduler execution role

이 역할은 EventBridge Scheduler가 지정된 ECS Task를 시작하고, 최종 호출 실패를 Scheduler DLQ에 기록할 때 사용한다. 14절 schedule 생성 화면의 자동 역할 생성에 맡기지 않고 지금 정확한 이름과 최소 권한으로 만든다. IAM role은 생성 후 이름을 바꿀 수 없고, 9.2 application role의 `iam:PassRole`이 이 정확한 role ARN을 참조하기 때문이다.

#### 9.3.1 Role과 trust policy 생성

1. IAM **Roles → Create role**을 누른다.
2. Trusted entity type에서 **Custom trust policy**를 선택한다.
3. 아래 JSON의 `ACCOUNT_ID` 두 곳을 현재 12자리 계정 ID로 바꿔 붙여 넣는다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "scheduler.amazonaws.com"
      },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": {
          "aws:SourceAccount": "ACCOUNT_ID",
          "aws:SourceArn": "arn:aws:scheduler:ap-northeast-2:ACCOUNT_ID:schedule-group/inning-log-crawler"
        }
      }
    }
  ]
}
```

4. **Next**를 누른다.
5. 권한 선택 화면에서는 광범위한 관리형 정책을 추가하지 않고 **Next**를 누른다.
6. Role name에 `inning-log-crawler-scheduler`를 정확히 입력한다.
7. **Create role**을 누른다.

`aws:SourceArn`은 개별 schedule ARN이 아니라 14절에서 만들 schedule group ARN이어야 한다. schedule group이 아직 없어도 이 trust policy와 role은 먼저 생성할 수 있다.

#### 9.3.2 최소 실행 권한 추가

1. 생성한 `inning-log-crawler-scheduler` role을 연다.
2. **Add permissions → Create inline policy → JSON**을 선택한다.
3. 아래 JSON의 `ACCOUNT_ID` 다섯 곳을 모두 현재 12자리 계정 ID로 바꾼다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "RunOnlyCrawlerTask",
      "Effect": "Allow",
      "Action": "ecs:RunTask",
      "Resource": "arn:aws:ecs:ap-northeast-2:ACCOUNT_ID:task-definition/inning-log-crawler:*",
      "Condition": {
        "ArnEquals": {
          "ecs:cluster": "arn:aws:ecs:ap-northeast-2:ACCOUNT_ID:cluster/inning-log-crawler"
        }
      }
    },
    {
      "Sid": "PassOnlyCrawlerTaskRoles",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": [
        "arn:aws:iam::ACCOUNT_ID:role/inning-log-crawler-task-execution",
        "arn:aws:iam::ACCOUNT_ID:role/inning-log-crawler-task"
      ],
      "Condition": {
        "StringEquals": {
          "iam:PassedToService": "ecs-tasks.amazonaws.com"
        }
      }
    },
    {
      "Sid": "WriteOnlySchedulerDlq",
      "Effect": "Allow",
      "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:ap-northeast-2:ACCOUNT_ID:inning-log-crawler-scheduler-dlq"
    }
  ]
}
```

4. JSON 검색으로 `ACCOUNT_ID`가 `0건` 남았는지 확인한다.
5. **Next**를 누르고 Policy name은 `inning-log-crawler-scheduler-execution`으로 입력한다.
6. 정책을 생성한다.

> 위 설명의 `ACCOUNT_ID` 개수는 JSON 문자열 내 등장 횟수를 의미한다. 콘솔 편집 과정에서 정책을 수정했다면 개수를 세기보다 `ACCOUNT_ID`가 하나도 남지 않았는지를 최종 기준으로 삼는다.

#### 9.3.3 완료 확인

- [ ] Role name이 `inning-log-crawler-scheduler`와 정확히 일치함
- [ ] Trusted service가 `scheduler.amazonaws.com`임
- [ ] Trust policy의 SourceAccount가 현재 계정임
- [ ] Trust policy의 SourceArn이 `schedule-group/inning-log-crawler`임
- [ ] Inline policy가 crawler task definition, 두 task role, Scheduler DLQ만 허용함
- [ ] AdministratorAccess와 AmazonECS_FullAccess가 없음

14절에서 schedule을 만들 때는 **Create new role for this schedule**이 아니라 **Use existing role**을 선택하고 `inning-log-crawler-scheduler`를 지정한다. AWS는 콘솔에서도 Scheduler 실행 role을 자동 생성할 수 있지만, 이 프로젝트는 코드에서 같은 role ARN으로 일회성 schedule도 만들기 때문에 고정 이름의 기존 role 하나를 사용한다.

## 10. 6단계 — VPC와 Security Group

1차 학생 프로젝트 구성은 NAT Gateway 비용을 피하기 위해 **public subnet + task public IP + inbound 없음**으로 한다. 크롤러는 서버 포트를 열지 않는 batch task다.

### 10.1 public subnet 확인

1. 상단 검색창에서 `VPC`를 연다.
2. **Your VPCs**에서 사용할 VPC를 선택한다. 초기에는 default VPC를 사용할 수 있다.
3. 선택한 VPC의 **Resource map**을 연다. 리소스 맵에서 초록색 subnet은 public, 파란색 subnet은 private 표시다.
4. **Subnets**에서 서로 다른 가용 영역의 초록색 public subnet 두 개를 기록한다. 이름에 `private`가 들어간 기존 subnet은 색상과 실제 route가 public으로 보여도 이 프로젝트에서는 선택하거나 수정하지 않는다.
5. 두 subnet이 연결된 route table을 열어 Routes에서 `0.0.0.0/0 → igw-...`가 `Active`인지 확인한다. subnet 이름이 아니라 이 route가 public 여부의 최종 기준이다.
6. 선택한 VPC ID, subnet ID 두 개, route table ID를 Git에서 제외되는 `.env.aws.local`에 기록한다.

현재 계정에서 선택한 초기값은 다음과 같다. 실제 ID는 공개 문서에 고정하지 않고 로컬 배포 기록에서 관리한다.

```text
default VPC 1개
서로 다른 가용 영역의 기본 public subnet 2개
기존 inning-log-private-a subnet은 선택 대상에서 제외
```

Fargate 실행 시에는 이 두 subnet을 지정하고 **Assign public IP = Enabled**로 설정한다.

### 10.2 전용 Security Group 생성

1. VPC 왼쪽 메뉴에서 **Security groups**를 누른다.
2. **Create security group**을 누른다.
3. Security group name에 `inning-log-crawler-egress`를 입력한다.
4. VPC는 위에서 선택한 VPC로 지정한다.
5. **Inbound rules는 한 개도 추가하지 않는다.**
6. Outbound 기본 All traffic 규칙을 삭제한다.
7. Outbound rule에 다음 하나를 추가한다.

| Type | Protocol | Port | Destination |
| --- | --- | --- | --- |
| HTTPS | TCP | 443 | `0.0.0.0/0` |

8. **Create security group**을 누른다.

Fargate가 public subnet에서 외부 HTTPS와 ECR에 접근하려면 task ENI에 public IP가 있어야 한다. 이후 RunTask와 Scheduler 설정에서 **Assign public IP = Enabled**를 선택한다. [Fargate outbound networking](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/networking-outbound.html)

이 구성의 IP는 고정되지 않는다. KBO 허가가 고정 IP를 요구하면 public IP 구성을 그대로 운영하지 말고, 비용과 정책을 다시 검토한 뒤 고정 egress 구조로 변경한다.

## 11. 7단계 — ECS Cluster 생성

1. 상단 검색창에서 `Elastic Container Service` 또는 `ECS`를 연다.
2. 왼쪽에서 **Clusters**를 누른다.
3. **Create cluster**를 누른다.
4. Cluster name에 `inning-log-crawler`를 입력한다.
5. Infrastructure는 **AWS Fargate (serverless)**만 사용한다.
6. EC2 Auto Scaling Group이나 EC2 capacity는 추가하지 않는다.
7. Container Insights는 초기 비용을 최소화하려면 비활성으로 시작하고, 기본 CloudWatch Logs와 Scheduler metric을 사용한다.
8. 위의 공통 태그를 추가한다.
9. **Create**를 누른다.

ECS Service는 생성하지 않는다. Service는 원하는 task 수를 계속 유지하므로 종료한 크롤러를 다시 켤 수 있다.

## 12. 8단계 — Fargate Task definition 생성

1. ECS 왼쪽 메뉴에서 **Task definitions**를 누른다.
2. **Create new task definition**을 누른다.
3. Task definition family에 `inning-log-crawler`를 입력한다.
4. Launch type 또는 Infrastructure requirements에서 **AWS Fargate**를 선택한다.
5. Operating system은 **Linux**를 선택한다.
6. CPU architecture는 Docker 이미지를 빌드한 아키텍처와 맞춘다.
   - `linux/arm64` 이미지라면 **ARM64**
   - 일반 `linux/amd64` 이미지라면 **X86_64**
7. Network mode는 Fargate 필수값인 `awsvpc`를 사용한다.
8. Task size는 처음에 다음 값으로 시작한다.

| 항목 | 값 |
| --- | --- |
| CPU | `.25 vCPU` 또는 `256` |
| Memory | `1 GB` |

9. Task execution role은 `inning-log-crawler-task-execution`을 선택한다.
10. Task role은 `inning-log-crawler-task`를 선택한다.

### Container 정의

1. Container name은 `crawler`다.
2. Image URI에는 5.7절에서 검증한 ECR의 불변 Git SHA 태그 **전체 URI**를 입력한다. `latest`를 선택하지 않는다.
3. Essential container는 **Yes**로 둔다.
4. **Private registry authentication**은 끈 상태로 둔다. 같은 계정의 ECR 인증은 Task execution role이 담당한다.
5. 화면에 기본 Port mapping `80/TCP/HTTP` 행이 생겼다면 오른쪽 **Remove/제거**를 눌러 삭제한다. `Port mappings`가 `0개`인 상태가 정상이다. 이 크롤러는 요청을 받는 웹 서버가 아니다.
6. Entry point와 Command는 모두 비워 둔다. 이미지의 안전한 기본 명령 `--plan-day --profile fixture --dry-run`이 실행된다. 계획·경기 모드는 Scheduler가 container override로 **인수만** 지정한다.
7. 첫 Task definition revision은 fixture smoke test 전용이다. **Environment variables → Add environment variable**을 세 번 눌러 행 세 개를 만든다. AWS 콘솔은 여러 줄 `KEY=VALUE`를 한 번에 붙이는 입력창이 아니므로 각 행의 Key와 Value 칸에 아래 값을 하나씩 복사한다.

첫 번째 행:

```text
Value type: Value
Key: AWS_REGION
Value: ap-northeast-2
```

두 번째 행:

```text
Value type: Value
Key: TZ
Value: Asia/Seoul
```

세 번째 행:

```text
Value type: Value
Key: CRAWLER_PROFILE
Value: fixture
```

입력을 마치면 Environment variables 영역에 다음 세 쌍만 보여야 한다.

```text
AWS_REGION        ap-northeast-2
TZ                Asia/Seoul
CRAWLER_PROFILE   fixture
```

`CRAWLER_KILL_SWITCH`는 초기 fixture revision에 넣지 않는다. `fixture` profile은 설정 파일에서 안전하게 허용되고, `kbo-locked` profile은 설정 파일에서 이미 kill switch가 켜져 있다. 여기에 `true`를 넣으면 fixture 정책 검사 결과까지 차단 상태로 바뀐다.

DynamoDB, SQS, Scheduler, subnet과 security group 관련 `CRAWLER_*` 값도 지금은 넣지 않는다. fixture dry-run은 AWS API나 외부 네트워크를 사용하지 않는다. 실제 KBO 전환 시에는 16절에서 별도 Task definition revision을 만들고 검토된 전체 값을 추가한다.

8. Logging은 **Use log collection** 또는 `awslogs`를 선택한다.
9. Log group은 `/inning-log/crawler`, Region은 `ap-northeast-2`, stream prefix는 `ecs`로 지정한다.
10. Read-only root filesystem 옵션이 있으면 활성화한다. 임시 파일이 필요하면 `/tmp`만 사용하도록 코드를 확인한다.
11. Health check와 volume은 1차 구성에서 추가하지 않는다.
12. **Create**를 누른다.

Fargate task definition은 CPU·메모리, `awsvpc`, 실행 역할과 task role을 분리해 지정한다. [.25 vCPU는 512MiB·1GB·2GB 조합을 지원](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)하며, 로그는 `awslogs`로 CloudWatch에 전달할 수 있다.

## 13. 9단계 — fixture 수동 실행

예약 실행을 만들기 전에 외부 요청 0건인 fixture로 ECS 경로를 검증한다.

1. ECS **Clusters → inning-log-crawler**를 연다.
2. **Tasks** 탭에서 **Run new task**를 누른다.
3. Compute options에서 **Launch type → FARGATE**를 선택한다.
4. Task definition family `inning-log-crawler`와 최신 revision을 선택한다.
5. Desired tasks는 `1`이다.
6. Networking에서 위 VPC와 public subnet 두 개를 선택한다.
7. Security group은 `inning-log-crawler-egress`를 선택한다.
8. **Public IP를 Enabled**로 설정한다.
9. Container override의 Command에 다음 인수를 순서대로 입력한다. `node`나 `src/fargate/main.js`는 이미지 ENTRYPOINT에 이미 있으므로 입력하지 않는다.

```text
--plan-day
--profile
fixture
--dry-run
```

10. **Create/Run task**를 누른다.
11. Tasks 목록에서 상태가 `RUNNING`을 거쳐 `STOPPED`가 되는지 확인한다.
12. Task 상세에서 Exit code가 `0`인지 확인한다.
13. **Logs** 탭 또는 CloudWatch `/inning-log/crawler`에서 다음을 확인한다.

- profile이 `fixture`
- `dryRun`이 `true`
- `externalRequests`가 `0`
- 정책 검사 우회나 비밀값 로그가 없음

실패하면 schedule을 만들기 전에 17절을 따라 원인을 해결한다.

### 13.1 실제 AWS 저장 경로를 fixture로 끝까지 검증

위 `fixture --dry-run`은 컨테이너 시작만 검증하며 DynamoDB, SQS와 일회성 Scheduler에는 쓰지 않는다. 새 코드와 이미지를 배포한 뒤에는 `fixture-aws` 프로필로 **외부 요청 0건 + 실제 AWS 쓰기**를 한 번 검증한다. 이 프로필은 번들된 `fixtures/kbo/*.html`만 읽는다.

새 Task definition revision에는 기존 세 환경변수와 함께 아래 AWS 연결값을 모두 `Value` 유형으로 추가한다. `valueFrom`을 선택하지 않는다.

```text
CRAWLER_PROFILE=fixture-aws
CRAWLER_STATE_TABLE=inning-log-crawler-state
CRAWLER_SNAPSHOT_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/590385682315/inning-log-game-snapshots
CRAWLER_SCHEDULER_GROUP=inning-log-crawler
CRAWLER_SCHEDULER_ROLE_ARN=arn:aws:iam::590385682315:role/inning-log-crawler-scheduler
CRAWLER_SCHEDULER_DLQ_ARN=arn:aws:sqs:ap-northeast-2:590385682315:inning-log-crawler-scheduler-dlq
CRAWLER_ECS_CLUSTER_ARN=arn:aws:ecs:ap-northeast-2:590385682315:cluster/inning-log-crawler
CRAWLER_ECS_TASK_DEFINITION=arn:aws:ecs:ap-northeast-2:590385682315:task-definition/inning-log-crawler
CRAWLER_ECS_CONTAINER_NAME=crawler
CRAWLER_ECS_SUBNET_IDS=subnet-07c58bcb726562065,subnet-0c5afbe9c4bc54ec5
CRAWLER_ECS_SECURITY_GROUP_IDS=sg-07145829faeca82c0
CRAWLER_ECS_ASSIGN_PUBLIC_IP=true
```

수동 실행의 container override에는 다음 인수만 넣는다. 날짜는 번들 fixture에 존재하는 값이며 실제 KBO 요청 날짜가 아니다.

```text
--plan-day
--profile
fixture-aws
--date
2026-08-27
```

성공 조건은 다음과 같다.

- ECS Task가 exit code `0`으로 종료한다.
- 로그에 `profile:"fixture-aws"`, `month:"2026-08"`, `monthGameCount:4`, `requestMetrics.logicalRequests:0`이 나온다.
- DynamoDB에 `SCHEDULE#MONTH#2026-08`, `LATEST#2026-08-27`, `DAY#2026-08-27`이 자동 생성된다.
- SQS에 월간 일정 snapshot과 당일 snapshot이 각각 내용 변경 시 한 번 발행된다.
- EventBridge Scheduler에 `inning-log-crawler-run-2026-08-27`이 하나만 생성된다.

검증 후 fixture 날짜가 지난 일회성 schedule은 삭제한다. 매일 06:00 반복 schedule은 계속 `fixture --dry-run`으로 두며, `fixture-aws`를 상시 fixture 데이터 공급용으로 사용하지 않는다.

## 14. 10단계 — EventBridge Scheduler 일일 계획 작업

Scheduler 콘솔은 ECS 콘솔보다 기능이 많으므로 EventBridge Scheduler에서 직접 만든다. [AWS 공식 ECS 예약 작업 절차](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/tasks-scheduled-eventbridge-scheduler.html)

이 절의 `fixture --dry-run` target은 매일 실행 경로의 안전성만 확인하며 KBO, DynamoDB와 SQS를 호출하지 않는다. AWS 저장 통합 시험은 13.1절에서 별도로 수행한다.

1. 상단 검색창에서 `EventBridge Scheduler`를 연다.
2. 왼쪽 **Schedule groups**에서 **Create schedule group**을 누른다.
3. 이름을 `inning-log-crawler`로 입력해 생성한다.
4. 왼쪽 **Schedules**로 이동한다.
5. **Create schedule**을 누른다.
6. Schedule name은 `inning-log-crawler-plan-daily`다.
7. Schedule group은 `inning-log-crawler`를 선택한다.
8. Occurrence는 **Recurring schedule**을 선택한다.
9. Schedule type은 **Cron-based schedule**이다.
10. Cron expression은 다음과 같다.

```text
cron(0 6 * * ? *)
```

11. Flexible time window는 **Off**로 설정한다.
12. Timezone은 **Asia/Seoul**을 선택한다.
13. 필요하면 정규시즌 시작·종료에 맞춰 Start date와 End date를 지정한다.
14. **Next**를 누른다.
15. Target 선택에서 **All APIs**를 누른다.
16. 검색창에 `ECS`를 입력하고 **Amazon ECS**를 선택한다.
17. API는 `RunTask`를 선택한다.
18. ECS cluster는 `inning-log-crawler`를 선택한다.
19. Task definition은 `inning-log-crawler`의 배포 revision을 선택한다.
20. Launch type은 **FARGATE**다.
21. VPC, public subnet 두 개와 `inning-log-crawler-egress` security group을 선택한다.
22. Assign public IP는 **Enabled**로 둔다.
23. Task count는 `1`이다.
24. Target input 또는 container override에 안전한 fixture `plan-day` dry-run 인수만 넣는다. 이미지 ENTRYPOINT가 `node src/fargate/main.js`를 제공하므로 `node`와 파일 경로를 다시 넣지 않는다.

```json
{
  "containerOverrides": [
    {
      "name": "crawler",
      "command": ["--plan-day", "--profile", "fixture", "--dry-run"]
    }
  ]
}
```

25. **Next**를 누른다.
26. Schedule state는 **Enabled**로 둔다. 단, KBO 허가 전 실제 프로필은 fixture와 kill switch 유지 상태여야 한다.
27. Retry policy는 켜고 Maximum age `1 hour`, Maximum retries `2`로 설정한다.
28. DLQ는 **Select an Amazon SQS queue in my AWS account**를 선택한다.
29. `inning-log-crawler-scheduler-dlq`를 선택한다.
30. Permissions는 **Use existing role**을 선택하고 9.3절에서 만든 `inning-log-crawler-scheduler`를 지정한다. 이 화면에서 새 role을 만들지 않는다.
31. **Next → Review → Create schedule**을 누른다.
32. 목록에서 State가 `Enabled`인지, 다음 실행 시각이 KST 기준 의도와 일치하는지 확인한다.

## 15. 계획 작업이 만드는 일회성 경기 schedule

이 부분은 콘솔에서 매일 손으로 만드는 설정이 아니라 `plan-day` 코드가 AWS SDK로 만든다.

계획 작업은 다음 값을 사용한다.

- Schedule group: `inning-log-crawler`
- Name: `inning-log-crawler-run-YYYY-MM-DD`
- Type: one-time `at(...)`
- 실행 시각: 가장 이른 경기 예정 시각 60분 전
- Timezone: `Asia/Seoul`
- Target: 같은 ECS cluster와 task definition의 `RunTask`
- Command override: `run-game-window`
- Retry: 최대 2회, 최대 age 30분
- DLQ: `inning-log-crawler-scheduler-dlq`
- Action after schedule completion: `DELETE`

`DELETE`를 지정하면 한 번 실행된 schedule 리소스가 자동 삭제된다. [일회성 schedule 자동 삭제](https://docs.aws.amazon.com/scheduler/latest/UserGuide/managing-schedule-delete.html)

경기 작업 명령 예시는 다음 형태다.

```json
{
  "containerOverrides": [
    {
      "name": "crawler",
      "command": ["--run-game-window", "--profile", "kbo-live", "--date", "2026-08-26"]
    }
  ]
}
```

동일 이름이 이미 존재하면 무조건 새로 만들지 말고 예정 시각·task revision·입력을 비교해 필요한 경우에만 update한다. DynamoDB `LEASE#PLAN#YYYY-MM-DD` 조건부 획득으로 중복 계획 작업을 막는다.

## 16. 실 데이터 전환 순서

AWS 인프라가 정상이라고 바로 KBO profile을 켜지 않는다.

1. fixture 수동 Task가 exit code 0인지 확인한다.
2. fixture 일일 Scheduler가 `--dry-run`으로 정상 실행되고 외부 요청이 0인지 확인한다.
3. `npm test`에서 일회성 Scheduler payload, DynamoDB lease, SQS idempotency와 종료 보정 시각 테스트가 통과했는지 확인한다.
4. 서면 허가 후 첫 제한 실행에서 `inning-log-crawler-run-YYYY-MM-DD`가 하나만 생성되고 같은 계획을 재실행해도 중복되지 않는지 확인한다.
5. 주입형 시계 테스트의 hard timeout·종료 보정 검증을 통과하고, 첫 제한 실행에서도 Task가 종료되는지 확인한다.
6. Scheduler disable이 즉시 새 Task 생성을 막는지 확인한다.
7. 정책 허가 문서에서 두 KBO 경로와 호출량·저장 필드·robots 예외를 확인한다.
8. 코드 리뷰로 정책 카탈로그와 profile을 갱신한다.
9. `policy:check`와 전체 테스트를 통과한다.
10. 처음에는 한 경기일만 제한적으로 실행하고 CloudWatch 요청량을 대조한다.

실행 허가를 실제로 확보했다면 새 Task definition revision에 `CRAWLER_PROFILE=kbo-live`를 넣고, 아래 값을 허가서와 그대로 대응시킨다. 예시 문구나 임의 날짜를 복사하지 않는다.

| Key | 값의 출처 |
| --- | --- |
| `CRAWLER_PROFILE` | `kbo-live` |
| `CRAWLER_ENABLED` | `true` |
| `CRAWLER_KILL_SWITCH` | 최종 승인 시에만 `false` |
| `CRAWLER_OPERATOR_CONTACT` | 실제 연락 가능한 `mailto:` 주소 |
| `CRAWLER_AUTH_STATUS` | 허가가 유효할 때만 `approved` |
| `CRAWLER_AUTH_EVIDENCE` | 비밀 원문이 아닌 내부 허가 문서 식별자 |
| `CRAWLER_AUTH_REVIEWED_AT` | 7일 이내 ISO 8601 검토 시각 |
| `CRAWLER_AUTH_EXPIRES_AT` | 검토 시각보다 뒤이고 아직 지나지 않은 만료 시각 |
| `CRAWLER_AUTH_SCOPES` | `schedule-page,scoreboard-page,robots-disallow-override` |
| `CRAWLER_ROBOTS_EXCEPTION_GRANTED` | 두 경로 예외가 서면에 있을 때만 `true` |

실 운영 profile은 환경변수 몇 개를 콘솔에서 즉흥적으로 바꿔 만들지 않는다. 검토된 config 파일 또는 버전 관리되는 IaC로 task definition revision을 새로 등록한다.

## 17. 모니터링과 중단

### 매일 확인할 화면

- ECS **Cluster → Tasks → Stopped**: 종료 사유와 exit code
- CloudWatch **Logs → /inning-log/crawler**: 정책 차단·파서 오류·429·CAPTCHA
- EventBridge Scheduler **Schedules**: 다음 실행과 최근 상태
- SQS `inning-log-crawler-scheduler-dlq`: Scheduler 호출 실패
- SQS `inning-log-game-snapshots-dlq`: 백엔드 처리 실패
- DynamoDB: 오래 남은 `LEASE#` 항목과 circuit 상태

Scheduler는 CloudWatch `AWS/Scheduler` namespace에 `TargetErrorCount`, `InvocationDroppedCount`와 DLQ 지표를 제공한다. 두 값이 1 이상이면 알림을 만들 것을 권장한다. [Scheduler CloudWatch 지표](https://docs.aws.amazon.com/scheduler/latest/UserGuide/monitoring-cloudwatch.html)

### 즉시 전체 중단

1. EventBridge Scheduler **Schedules**에서 `inning-log-crawler-plan-daily`를 선택한다.
2. **Disable**을 눌러 새 계획 실행을 막는다.
3. `inning-log-crawler` schedule group의 당일 `run-YYYY-MM-DD` schedule도 disable 또는 delete한다.
4. ECS **Cluster → Tasks → Running**에서 현재 crawler task를 선택한다.
5. **Stop**을 누르고 reason에 사고 번호 또는 중단 사유를 입력한다.
6. 배포 설정의 `CRAWLER_KILL_SWITCH=true`를 확인한다.
7. SQS에 이미 들어간 미처리 결과의 소비도 필요하면 중지한다.

401·403·429, CAPTCHA, robots 또는 약관 변경, 허가 만료, 예상 밖 요청량은 위 절차의 즉시 중단 조건이다.

## 18. 자주 발생하는 문제

### Task가 `PROVISIONING` 또는 `PENDING`에서 멈춤

- public subnet route가 Internet Gateway를 향하는지 확인한다.
- RunTask에서 Assign public IP가 Enabled인지 확인한다.
- task execution role에 `AmazonECSTaskExecutionRolePolicy`가 있는지 확인한다.
- ECR 이미지 URI와 CPU architecture가 일치하는지 확인한다.

### `CannotPullContainerError`

- ECR repository와 ECS task가 모두 `ap-northeast-2`인지 확인한다.
- 이미지 태그가 실제로 존재하는지 확인한다.
- execution role과 public outbound 443을 확인한다.

### Task는 시작되지만 바로 exit code 1

- ECS Task 상세의 **Logs** 탭을 연다.
- 필수 환경변수, 설정 경로와 명령 override를 확인한다.
- DynamoDB 또는 SQS `AccessDenied`면 application task role을 확인한다.
- KBO가 아니라 fixture에서도 실패하는지 먼저 분리한다.

### 계획 작업이 일회성 schedule을 만들지 못함

- application task role의 `scheduler:CreateSchedule`을 확인한다.
- `iam:PassRole` 대상이 `inning-log-crawler-scheduler` 하나로 정확한지 확인한다.
- schedule group 이름과 리전이 일치하는지 확인한다.
- 같은 이름 schedule이 이미 있다면 create가 아니라 idempotent update 경로인지 확인한다.

### 동일 날짜 Task가 두 개 실행됨

- DynamoDB lease가 conditional expression으로 구현되었는지 확인한다.
- lease TTL만 믿지 말고 owner와 만료 시각을 비교하는지 확인한다.
- EventBridge 재시도와 수동 RunTask가 겹쳤는지 CloudTrail과 Scheduler 지표를 확인한다.

### 비용이 예상보다 큼

- ECS Service를 실수로 만들어 desired task가 유지되고 있지 않은지 확인한다.
- NAT Gateway와 Load Balancer가 만들어졌는지 확인한다.
- public IPv4, CloudWatch 로그 무기한 보존과 오래된 ECR 이미지를 확인한다.
- 일회성 schedule의 Action after completion이 `DELETE`인지 확인한다.

## 19. 초기 검증 후 IaC로 전환

콘솔에서 fixture 전체 경로를 한 번 검증한 뒤에는 같은 리소스를 Terraform, CDK 또는 CloudFormation 중 하나로 옮긴다. 콘솔과 IaC가 같은 리소스를 동시에 관리하지 않도록 `ManagedBy` 태그를 바꾸고 변경 창구를 하나로 제한한다.

배포 파이프라인은 장기 AWS access key 대신 GitHub Actions OIDC와 최소 권한 role을 사용하고 다음 순서를 권장한다.

```text
npm ci
  → npm test
  → config:check / policy:check
  → Docker build
  → ECR push with git SHA
  → ECS task definition 새 revision 등록
  → fixture smoke task
  → Scheduler target revision 갱신
```

실 데이터 profile 활성화는 일반 이미지 배포와 분리된 승인 단계로 둔다.

## 20. AWS 리소스 정리 순서

실습 또는 프로젝트 종료 시에는 먼저 schedule을 disable하고 running task가 없는지 확인한 다음 정리한다.

1. EventBridge Scheduler의 일일·일회성 schedule과 schedule group
2. ECS의 running task, task definition revision과 cluster
3. ECR 이미지와 repository
4. SQS 결과 queue와 DLQ
5. DynamoDB 상태 table
6. CloudWatch log group
7. crawler 전용 IAM roles와 inline policies
8. crawler 전용 security group

공용 VPC, subnet, Internet Gateway와 팀이 함께 쓰는 IAM role은 이 문서만 보고 삭제하지 않는다.
