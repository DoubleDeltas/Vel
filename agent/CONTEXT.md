# VEL 프로젝트 작업 맥락

에이전트(및 사람)가 작업을 이어받을 때 읽는 요약. 세부 명세는 `docs/`가 원본이며, 이 문서는 **결정 사항과 그 이유, 진행 상태, 남은 과제**를 기록한다.

마지막 갱신: 2026-11-17 (A~D 결정 반영)

---

## 1. 목표
연산자 오버로딩 등 문법 확장이 없는 Java에서 수치·벡터·행렬 계산을 직관적으로 쓰기 위한 **VEL(Vector Expression Language)** 을 정의한다.
- 문법: ANTLR 4
- 결과: 컴파일 타임에 최적의 Java 코드로 전처리

## 2. 전처리 방식 (결정됨)

VEL 코드를 어디에 쓰느냐에 따라 세 가지 방식이 있다.

| 방식 | VEL 코드 위치 | 처리기 | 우선순위 |
|---|---|---|---|
| B | `@Vel("...")` 추상 메서드 본문 | Annotation Processor | **1차** |
| C | `vel("...")` 인라인 식 | Javac Plugin (`-Xplugin:Vel`) | **1차** |
| A | 별도 `.vel` 파일 | Gradle 소스 생성 태스크 | 추후 확장 |

- **Kotlin도 지원한다 (2026-11-17 결정).** 대상은 Kotlin/JVM. 타입 매핑은 `docs/type-mapping/kotlin.md`.
  - B(`@Vel` 함수)는 **KSP**로 처리한다(결정됨, `vel-ksp`). kapt는 쓰지 않는다.
  - C(인라인 `vel()`)는 **Kotlin 컴파일러 플러그인**으로 처리한다(결정됨, `vel-kotlinc`). K2 대응이며 IR 단계(`IrGenerationExtension`)에서 동작한다. 나중의 코드 치환도 IR에서 한다.
- **Java 문법에 직접 섞는 방식(예: `Vec3 r = p + v*dt;`)은 배제한다.** javac 파서가 플러그인보다 먼저 실행되므로 불가능하다.
- **핵심 원칙**: 파서, 타입 검사, IR, 최적화, Java 코드 생성은 javac에 의존하지 않는 `vel-core`에 둔다. 각 처리기는 코어를 감싸는 얇은 껍데기로 만든다.
- **C 방식은 javac 플러그인에서 처리한다 (결정됨).** 타입 분석이 끝난 AST에서 지역 변수 타입을 읽어 타입 검사와 코드 치환을 모두 플러그인에서 한다.
  - 이를 위해 javac 내부 API(`com.sun.tools.javac.tree.TreeMaker` 등)와 `--add-exports`에 의존하는 것을 감수한다.
  - B 방식(`@Vel`)은 그대로 AP가 담당한다.

## 3. 빌드 / 모듈 구성

- 환경: Gradle 9.8 (Groovy DSL), Java 21 toolchain, JUnit 6, ANTLR 4.13.2, Kotlin 2.4.21, KSP 2.3.14, kctfork 0.14.0(KSP 테스트)
- Kotlin·KSP 플러그인 버전은 루트 `build.gradle`의 `plugins { ... apply false }`에서 한 번만 선언한다.
- 그룹/패키지: `com.doubledeltas` / `com.doubledeltas.vel.*`

| 모듈 | 내용 |
|---|---|
| `vel-runtime` | `@Vel`(SOURCE 유지), `@Dim(java=…|vel=…)`(CLASS 유지, 배열 크기 지정), `VelInline.vel(String)`(플러그인 미적용 시 예외를 던지는 자리표시자). 사용자 런타임 클래스패스에 올라가는 유일한 모듈 |
| `vel-core` | `Vel.g4`, `VelFrontend.parseProgram/parseExpression`, `ParseResult`, `VelDiagnostic`. 타입 모델은 `core.type` 패키지(`Card`, `Field`, `Resolution`, `Promotion`, `ScalarType`, `ArrayType`, `Shapes`, `BinaryOperator`, `TypeRules`) |
| `vel-processor` | `VelProcessor`: `@Vel` 메서드의 배치(추상 여부, 소속)와 문법을 검증하고, 오류를 어노테이션 값 위치에 보고 |
| `vel-javac` | `VelJavacPlugin`: ANALYZE 단계 이후 `VelInline.vel` 호출(정규화된 이름으로 판별)을 찾아 문자열 리터럴 여부와 문법을 검증. `autoStart=true` |
| `vel-examples` | B와 C 사용 예. `annotationProcessor`로 processor와 javac 모듈을 연결 |
| `vel-ksp` | `VelSymbolProcessor`(Kotlin): Kotlin의 `@Vel` 함수 배치(추상 여부, 소속)와 문법을 검증하고 오류를 어노테이션 위치에 보고. `vel-processor`의 Kotlin 대응 |
| `vel-kotlinc` | Kotlin 컴파일러 플러그인: IR 단계에서 `VelInline.vel` 호출(`CallableId`로 판별)을 찾아 문자열 리터럴 여부(템플릿 불가)와 문법을 검증. 오류는 `IrDiagnosticReporter`로 `VEL_ERROR`/`VEL_WARNING` 진단을 보고 |
| `vel-examples-kotlin` | Kotlin B·C 사용 예. `ksp project(':vel-ksp')`, `kotlinCompilerPluginClasspathMain project(':vel-kotlinc')`로 연결 |

- **빌드 메모**:
  - antlr 플러그인이 ANTLR 도구 전체를 `api` 구성으로 흘려보내는 문제는 막아 두었다. 하위 모듈에는 `antlr4-runtime`만 전파된다.
  - 패키지는 `packageName` 속성으로 지정한다. `-package` 인자는 Gradle 9에서 deprecated다.
- **진행 상태**: 문법 검증과 오류 보고, 그리고 타입 모델(규칙 계산)까지 구현되어 있다. 파스 트리에 타입을 매기는 타입 검사와 코드 생성은 아직 없다. 테스트는 core 47(문법 24, 타입 23), processor 3, javac 4, ksp 4, kotlinc 5개이며 모두 통과한다.

### 배포 (GitHub Packages)
- 배포 대상 모듈: `vel-runtime`, `vel-core`, `vel-processor`, `vel-javac`, `vel-ksp`, `vel-kotlinc` (예제 모듈 제외). 좌표는 `com.doubledeltas:<모듈>:<버전>`
- 저장소: `https://maven.pkg.github.com/DoubleDeltas/Vel`. 루트 `build.gradle`에서 `maven-publish`를 일괄 설정한다(소스·javadoc jar 포함).
- 버전: 기본 `1.0-SNAPSHOT`, `-PvelVersion=...`로 덮어쓴다.
- 배포는 GitHub Actions(`.github/workflows/publish.yml`)로 한다.
  - Release 게시(태그 `v1.2.3`) → 버전 `1.2.3`
  - 수동 실행(`gh workflow run publish.yml`) → `1.0-SNAPSHOT`
  - 로컬 `gh` 토큰에는 `write:packages` 권한이 없으므로 로컬 배포는 하지 않는다.
- 배포 전 점검: `./gradlew publishAllPublicationsToBuildRepoRepository` (결과는 `build/repo`)
- 라이선스: **MIT** (`LICENSE`, 저작권자 `DoubleDeltas`). POM에도 라이선스 정보를 넣는다.
- **사용하는 쪽도 인증이 필요하다**: GitHub Packages의 Maven 저장소는 공개 패키지라도 읽을 때 `read:packages` 권한 토큰이 필요하다.

## 4. 문법 현황 (`vel-core/src/main/antlr/.../Vel.g4`)

- **진입 규칙**: `program`(B 방식, 문장 여러 개)과 `singleExpression`(C 방식, 식 1개)
- **문장**:
  - `return expr`
  - `target op expr`
    - `target`은 변수, 또는 그 원소·부분행렬(`m[i, j]`, `m[0:2, :]`)이다. 멤버 대입(`v.x = ...`)은 허용하지 않는다.
    - `op`는 `=`, `+= -= *= /= //= ///= %%= %%%= ^=`, `.+= .-= .*= ./= .^=`
  - `expr`
  - 줄바꿈이나 `;`로 구분한다.
- **식 우선순위** (높음 → 낮음):
  1. 기본 항
  2. 후위 연산: 메서드 호출 `.ID(args)`, 멤버 `.ID`, 인덱스 `[...]`, `'`(켤레 전치), `.'`(전치)
  3. `^` `.^` (오른쪽 결합)
  4. 단항 `-`, `+`
  5. `*` `/` `//` `///` `%%` `%%%` `.*` `./`
  6. `+` `-` `.+` `.-`
  7. 범위 `a:b`, `a:s:b` (MATLAB식, 끝 포함)
- **기본 항**:
  - 숫자, `true`, `false`
  - `ID(args)` 호출 (내장 함수, 정적 임포트)
  - `ID`
  - `(expr)` 괄호
  - `(a, b, c)` 튜플: 세로 연결 `[a; b; c]`. 원소가 스칼라면 열벡터다.
  - `[a, b; c, d]` 행렬 리터럴: 행 우선이며 `[a, b, c]`는 1×3 행벡터. 원소가 행렬이면 블록 행렬이다(`[A, B; C, D]`).
- **인덱스**: `[i, j]`의 각 자리에 식, 범위(`1:3`, `0:2:end`), 또는 `:`(차원 전체)를 쓴다. MATLAB식이며 인덱스는 0부터 시작한다.
- **렉서**:
  - 줄바꿈은 문장 구분자다. 단, 다음 경우에는 무시한다.
    - `(`, `[` 안
    - 이항 연산자, 대입 연산자, `,`, `:`, `.` 뒤 (다음 줄로 이어 씀)
  - 주석은 `#`(줄 주석)과 `/* */`. `//`, `///`는 몫 연산자라 주석이 아니다. 여러 줄에 걸친 블록 주석은 줄바꿈 하나로 취급한다.
  - 식별자는 Java 식별자와 같은 문자 집합이다(유니코드 문자, `$`, `_` 포함). C 방식에서 Java 지역 변수를 이름 그대로 참조하기 위해서다.
  - 숫자의 `.` 뒤에는 숫자가 반드시 와야 한다(`2.`는 불가). 그래서 `2.*x`는 `2 .* x`로 읽힌다.
  - 예약어는 Java 예약어와 겹치는 `return`, `true`, `false`뿐이다. 그래서 모든 Java 식별자를 VEL에서 참조할 수 있다.
  - `end`(인덱스 안의 마지막 인덱스)와 `j`(허수 단위)는 예약어가 아니라 미리 선언된 이름이다. 의미 분석에서 해석한다.
  - 모든 연산자에 이름 붙은 토큰이 있다(`PLUS`, `DOT_STAR` 등). 렉서 액션이 직전 토큰을 보고 줄 이어짐을 판단한다.
- **연산자 의미 (결정됨, MATLAB 방식)**:
  - `*` `/` `^`: 행렬 곱, 우측 나눗셈, 행렬 거듭제곱. 피연산자가 스칼라면 일반 산술이다(`/`는 참 나눗셈).
  - `.*` `./` `.^`: 성분별 연산
  - `.+` `.-`: `+` `-`의 별칭이다(원래 성분별이라 의미가 같음). 이항 연산만 있고 단항 `.-`는 없다.
  - 몫과 나머지는 두 쌍이다. 모두 원소별이다.
    - `/`: 참 나눗셈 (나머지 짝 없음)
    - `//`: 내림 몫(−∞ 방향, Python `//`, `Math.floorDiv`)과 `%%`: 내림 나머지(제수 부호, Python `%`, `Math.floorMod`)
    - `///`: 버림 몫(0 방향, Java 정수 `/`)과 `%%%`: 버림 나머지(피제수 부호, Java `%`)
    - 기호 개수 = 반올림 방향(2개 내림, 3개 버림). **`%`는 없다.** Java의 `a % b`는 `a %%% b`로 쓴다.
    - 이유: `%`를 남기면 Java 코드를 옮길 때 음수에서 결과가 조용히 달라질 수 있다. 또 `floorMod`와 Python `%`는 같은 연산이라 별도 연산자가 필요 없다.
  - `'`: 켤레 전치, `.'`: 단순 전치. 실수 값에서는 둘이 같다.
  - 해상도: 성분별 연산자는 대응 연산자의 규칙을 따른다. 행렬 `/`와 음의 지수 `^`는 역행렬이 필요해 정수 `/` 규칙을 적용한다(`type-system.md` 3.3절).
  - 이유: 정사각 행렬끼리의 `*`는 행렬 곱과 성분별 곱 모두 타입이 맞아서, 타입만으로는 의미를 정할 수 없다.
- **메서드 호출 (결정됨)**: Java 형태를 따른다. 동적 메서드는 `t.foo(x)`, 정적 메서드는 `T.foo(x)`. `foo(t, x)` 형태로는 부를 수 없다.
- **미리 선언된 이름**:
  - `j = complex(0, 1)`: 허수 단위 상수. 함수 문법이 완성되면 `complex(a, b)`로도 복소수를 만들 수 있다.
  - `end`: 인덱스 안에서 해당 차원의 마지막 인덱스(길이 − 1)
  - **이름 충돌 (결정됨)**: 미리 선언된 이름이 같은 이름의 Java 변수보다 우선한다. Java 변수는 Kotlin처럼 백틱으로 쓴다(`` `j` ``, `` `end` ``). 백틱 안에는 Java 식별자만 올 수 있다(토큰 `QUOTED_ID`). C 방식에서 루프 변수 `j`를 쓰려면 `` `j` ``로 써야 한다는 점에 유의.

## 5. 타입 시스템 결정 사항
원본: `docs/type-system.md`, `docs/type-mapping/java.md`

- **타입 구성**:
  - 모든 객체는 함수다.
  - `Array(d…, F)`는 0부터 시작하는 인덱스를 스칼라로 대응시키는 함수다.
  - `Mat(m,n)`은 `Array(m,n)`이다.
  - `Vec(n)`은 `Mat(n,1)`, 즉 열벡터다.
  - 스칼라는 `Mat(1,1)`이다.
- **크기 ⊤** (`type-system.md` 1.2절):
  - 크기를 모르는 차원은 `⊤`다. `n + ⊤ = ⊤`
  - 실제 길이 이상의 인덱스에 있는 원소는 null로 취급하고, 연산에 쓰이면 NPE가 발생한다.
  - **런타임 크기 검사 코드는 생성하지 않는다.** 크기 불일치는 NPE로 드러난다.
  - `⊤`가 섞인 원소별 연산의 결과 크기는 `⊤`이고, 실제 길이는 피연산자 길이의 최댓값이다.
  - 크기가 정해진 차원끼리 맞지 않으면 컴파일 오류다.
- **브로드캐스팅** (1.3절): MATLAB식 암시적 확장. 단, 크기가 **컴파일 타임에 1로 알려진** 차원만 확장한다. `⊤` 차원은 실행 시 길이가 1이어도 확장하지 않는다. 행렬 곱 `*`은 브로드캐스팅하지 않는다.
- **연결** (1.4절): `,`는 가로, `;`는 세로 연결. `[A, B; C, D]`는 `(a+b)×(c+d)` 블록 행렬이다.
- **불변성 (결정됨, 1.5절)**: 모든 값은 불변이다. `m[i] = x`는 MATLAB처럼 복사 후 다시 쓰기이며 `m`을 새 값에 다시 묶는다.
  - Java에서 받은 배열은 VEL이 수정하지 않는다. 복사 생략 최적화는 VEL이 만든 값에만 적용한다.
  - Java 메서드 호출의 부수 효과는 막지 않는다.
- **체**: `Bool`, `Real <: Complex`. Bool의 수학적 분류(체인지 반환인지)는 **따지지 않기로 함**.
- **해상도**: `Bool`, `Z(n)`, `Z+(n)`, `Z(∞)`, `F(n)`, `D(n)`(IEEE 754 십진 부동소수), `D(∞)`
- **승격 규칙** (`type-system.md` 3장):
  - `⊑`(정확히 표현 가능): 암시적 변환은 이 방향으로만 허용한다. `F`와 `D(n)` 사이는 서로 `⊑`가 아니다.
  - `⊔` 우선순위 표:
    - ∞ 정밀도 > 십진 부동소수 `D(n)` > 이진 부동소수 `F(n)` > 정수
    - 정수와 부동소수가 섞이면 부동소수 쪽을 따른다 (Java와 같음).
    - `D(n)`도 `F(n)`처럼 더 높은 너비로 승격한다. `F`와 섞이면 `D(max 너비)`다. 예: `D(32) ⊔ F(64) = D(64)`
  - 정수 연산 결과는 최소 32비트다 (Java와 같음).
  - 정수 `/` 정수는 참 나눗셈으로 `F(64)`다. `Z(∞)`가 포함되면 `D(∞)`다.
  - `x^(음이 아닌 정수 리터럴)`은 정수를 유지한다. 그 외의 지수는 `⊔ F(64)`다.
  - 고정 폭 정수 오버플로는 순환(wrap)한다.
  - 리터럴은 미정 해상도로 상대 피연산자를 따른다. 기본값은 `Z(32)`와 `F(64)`다.
  - **`D(∞)` 정밀도**: 중간 연산은 반올림 오차를 누적하지 않고, 최종 결과에서만 필요할 때 반올림한다.
    - `+ - ×`는 정확히 계산한다.
    - `/`는 분수 `p/q`로 지연해 최종 결과에서 한 번만 나눈다.
    - 무리 연산만 작업 정밀도로 중간 반올림한다.
    - 최종 결과 = 값이 VEL 밖으로 나가는 시점(return, Java 변수 대입, 외부 함수 인자, 해상도 변환). 나누어떨어지면 반올림 없이 정확한 값을 낸다.
    - Java 기본값: 최종 정밀도 `MathContext.DECIMAL128`, 작업 정밀도는 그 2배(68자리). 둘 다 추후 `@VelConfig`로 변경 가능.
- **Java 매핑**:
  - 원시 타입과 래퍼 타입은 위 해상도에 대응한다. `char`는 `Z+(16)`이다. 래퍼가 `null`이면 NPE.
  - `BigInteger`는 `Z(∞)`, `BigDecimal`은 `D(∞)`다. `D(n)`은 Java 타입이 없어서 `BigDecimal` + `MathContext.DECIMALn`으로 표현한다.
  - 시퀀스는 **배열과 `List`로 한정**한다.
  - `F[n]`은 `Vec(n)`이다.
  - `F[m][n]`은 `Mat(n,m)`이다. 차원을 역순으로 두는 **열 우선** 방식이며, `a[i][j]`는 `(j, i)`에 대응한다.
  - 크기는 기본 `⊤`이고, `@Dim`으로 지정한다. `@Dim(java = {...})`(Java 선언 순서, 바깥 배열부터) 또는 `@Dim(vel = {...})`(VEL 순서, 행 먼저) 중 정확히 하나를 쓴다. `value`는 없다. 지정한 크기는 검사하지 않고 신뢰한다.
  - 범위 밖 접근(`ArrayIndexOutOfBoundsException` 등)은 NPE로 보고한다.
  - jagged 배열: 안쪽 차원을 `⊤`로 보고 같은 규칙(범위 밖은 null → NPE)을 따른다.

### 타입 모델 구현 메모 (`vel-core` `core.type`)
- `ArrayType`은 차원을 정규형으로 둔다: 랭크는 최소 2(`Array(n)` = `Vec(n)` = `Mat(n,1)`), 2번째 이후 끝의 크기 1 차원은 지운다. 그래서 문서의 정의가 `equals`로 성립한다.
- 리터럴은 `Resolution.IntLiteral`/`DecimalLiteral`(값 포함)로 표현하고, 상대 피연산자를 만날 때 `Promotion.adopt`로 해상도를 정한다. 단항 `-`는 리터럴을 리터럴로 유지한다(`x * -1`이 `x`를 따르게).
- `j`는 `Complex` 필드 + 정수 리터럴 해상도(`ScalarType.IMAGINARY_UNIT`)로 둔다.
- `Promotion.join`(⊔)은 최소 연산 폭을 적용하지 않는다(연결용). 산술 결과(`Promotion.arithmetic`)에만 적용한다.
- 문서에 없어 구현하며 정한 것 (확인 필요):
  - 리터럴끼리의 연산은 각자 기본 해상도로 확정한 뒤 계산한다(상수 접기 없음). 그래서 `0.5 * 2 * x`(x: F(32))는 F(64)가 된다.
  - `A ^ B`에서 지수가 음이 아닌 정수 리터럴이 아니면 문서대로 `A ⊔ F(64)`이며, 지수 `B`의 해상도는 보지 않는다.
  - Bool은 `+`(OR), `*`(AND)와 그 성분별·행렬 곱만 허용한다. 나머지 연산은 타입 오류.
  - Complex에는 몫·나머지(`//` `///` `%%` `%%%`)가 정의되지 않는다.
  - 행렬 `/`의 제수는 정사각이어야 한다(MATLAB의 최소제곱 해는 지원하지 않음). `^`의 지수는 스칼라만(`2^A` 같은 행렬 지수는 미지원).
  - 범위의 경계는 실수 스칼라여야 하며, 모두 정수 리터럴이면 길이를 계산하고 아니면 `⊤`다. 원소 해상도는 경계의 ⊔(리터럴은 기본값).
  - 함수 타입은 아직 모델에 없다(`VelType`은 `ArrayType`만 허용). 함수 문법과 함께 추가한다.

## 6. 미정 / 추후 과제

- [ ] Kotlin 컴파일러 플러그인의 한계
  - IR 단계라서 오류가 IDE 편집기에는 표시되지 않고 빌드할 때만 나온다. IDE에 표시하려면 FIR 체커(`FirAdditionalCheckersExtension`)가 필요하다.
  - 컴파일러 플러그인 API는 Kotlin 버전마다 바뀌므로, Kotlin을 올릴 때 `vel-kotlinc`를 함께 확인해야 한다.
  - Gradle 사용자를 위한 Gradle 플러그인(`KotlinCompilerPluginSupportPlugin`)은 아직 없다. 지금은 `kotlinCompilerPluginClasspathMain`에 직접 추가한다.
- [ ] Kotlin 문자열 템플릿: `@Vel("...")` 안의 `$`는 Kotlin 템플릿으로 해석된다. VEL 식별자에 `$`가 있으면 `${'$'}`로 써야 한다.
- [ ] Kotlin 고유 기능의 매핑: `operator fun`을 정의한 사용자 타입, 기본값·이름 있는 인자, `IntRange` 등

- [ ] `@VelConfig` 설계: 최종 정밀도 `MathContext`, 작업 정밀도 등. 형식과 적용 범위(클래스, 메서드, 패키지)
- [ ] **함수 문법**: 사용자가 추후 직접 설계할 예정. 오버로딩, 가변 인자, 제네릭, `void` 처리, `complex(a, b)` 같은 내장 함수
- [ ] **제어문**: 지금은 다루지 않기로 함
- [ ] 비교·논리 연산: Bool 리터럴은 생겼지만 Bool 값을 만드는 연산자가 없다.
- [ ] 명시적 변환(cast)과 타입 표기 문법: `⊑`를 거스르는 대입에 필요
- [ ] 문법 구현 정리
  - `NUMBER`를 `INT`/`DECIMAL`로 분리(리터럴 해상도 규칙용)
  - 오류 메시지 개선: 인라인에 문장을 쓴 경우, 닫히지 않은 괄호
- [ ] 의미 분석에서 거부할 것: `@Dim`에 `java`와 `vel`을 둘 다 쓰거나 둘 다 생략한 경우, 행 길이가 다른 행렬 리터럴, 범위 안의 범위(`a:b:c:d`는 `a:(b:c):d`로 파싱됨), 인덱스 밖의 `:`·`end`, `return` 없는 본문
- [ ] `java.md` 각주: `Character.codePointAt(char[], 0)` 표현이 부정확하다. `char` 하나의 값은 그냥 `(int) c`다.
- [ ] 구현 순서 제안
  1. ~~`vel-core` 타입 모델(크기 `⊤`, 브로드캐스팅, 연결 포함)~~ 완료
  2. Java 매핑 모듈(`TypeMirror → VEL 타입`, `@Dim` 읽기, javax.lang.model 의존, 예: `vel-java-model`)
  3. 타입 검사
  4. B 방식 코드 생성(`<Owner>Vel` 구현 클래스)
  5. C 방식 치환(javac 플러그인, 내부 API 사용)

## 7. 작업 관례

- 문서는 한국어로 작성하며, 사용자가 직접 수정한다. 편집 전에 항상 최신 내용을 다시 읽는다.
- 수식 표기:
  - 벡터 값은 `\mathbf`로 쓴다 (예: $\mathbf d$, $\mathbf v$). `\vec`, `\boldsymbol` 등은 쓰지 않는다.
  - 타입 이름은 `\mathrm` (예: $\mathrm{Vec}$, $\mathrm{Real}$)
  - 체 변수는 `\mathbb` (예: $\mathbb K$)
- 줄바꿈은 LF로 고정한다(`.gitattributes`). 예외로 `*.bat`, `*.cmd`는 CRLF.
- 커밋은 사용자가 요청할 때만 한다. 원격은 `origin` = https://github.com/DoubleDeltas/Vel.git, 작업 브랜치는 `master`.
