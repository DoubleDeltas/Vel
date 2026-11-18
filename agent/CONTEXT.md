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
| `vel-core` | `Vel.g4`, `VelFrontend.parseProgram/parseExpression`, `ParseResult`, `VelDiagnostic`. VEL 대수 모델은 `core.type` 패키지(`Card`, `ScalarAlgebra`, `ArrayAlgebra`, `Constant`, `Arithmetic`, `Shapes`, `BinaryOperator`, `TypeRules`), Java 구체화 규칙은 `core.concretization` 패키지(`Resolution`, `ScalarType`, `Operand`, `Promotion`) |
| `vel-processor` | `VelProcessor`: `@Vel` 메서드의 배치(추상 여부, 소속)와 문법을 검증하고, 오류를 어노테이션 값 위치에 보고 |
| `vel-javac` | `VelJavacPlugin`: ANALYZE 단계 이후 `VelInline.vel` 호출(정규화된 이름으로 판별)을 찾아 문자열 리터럴 여부와 문법을 검증. `autoStart=true` |
| `vel-examples` | B와 C 사용 예. `annotationProcessor`로 processor와 javac 모듈을 연결 |
| `vel-ksp` | `VelSymbolProcessor`(Kotlin): Kotlin의 `@Vel` 함수 배치(추상 여부, 소속)와 문법을 검증하고 오류를 어노테이션 위치에 보고. `vel-processor`의 Kotlin 대응 |
| `vel-kotlinc` | Kotlin 컴파일러 플러그인: IR 단계에서 `VelInline.vel` 호출(`CallableId`로 판별)을 찾아 문자열 리터럴 여부(템플릿 불가)와 문법을 검증. 오류는 `IrDiagnosticReporter`로 `VEL_ERROR`/`VEL_WARNING` 진단을 보고 |
| `vel-examples-kotlin` | Kotlin B·C 사용 예. `ksp project(':vel-ksp')`, `kotlinCompilerPluginClasspathMain project(':vel-kotlinc')`로 연결 |

- **빌드 메모**:
  - antlr 플러그인이 ANTLR 도구 전체를 `api` 구성으로 흘려보내는 문제는 막아 두었다. 하위 모듈에는 `antlr4-runtime`만 전파된다.
  - 패키지는 `packageName` 속성으로 지정한다. `-package` 인자는 Gradle 9에서 deprecated다.
- **진행 상태**: 문법 검증과 오류 보고, 그리고 타입 모델(규칙 계산)까지 구현되어 있다. 파스 트리에 타입을 매기는 타입 검사와 코드 생성은 아직 없다. 테스트는 core 58(문법 27, 승격 13, 타입 규칙 18), processor 3, javac 4, ksp 4, kotlinc 5개이며 모두 통과한다.

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

사용자용 정리 문서: `docs/vel-grammar.md` (문법을 바꾸면 함께 고친다)

- **진입 규칙**: `program`(B 방식, 문장 여러 개)과 `singleExpression`(C 방식, 식 1개)
- **문장**:
  - `return expr`
  - `target op expr`
    - `target`은 변수, 또는 그 원소·부분행렬(`m[i, j]`, `m[0:2, :]`)이다. 멤버 대입(`v.x = ...`)은 허용하지 않는다.
    - `op`는 `=`, `+= -= *= /= //= ///= %%= %%%= ^=`, `.+= .-= .*= ./= .^=`, `o+=`
  - `pattern = expr` (구조 분해 대입, 결정됨): `(x, y, z) = v`, `[a, b; c, d] = m`. 각 대상은 원소 하나(스칼라)를 받고, 패턴과 값의 모양이 맞아야 한다(`TypeRules.destructure`). 대상은 일반 대입 대상과 같다. 행 길이 불일치·대상 중복은 `StructureChecker`가 거부. `=`만 가능. 블록 단위 분해는 오른쪽에 분할점을 쓴다(아래 분할 참고). `?`는 원소를 버리는 자리(결정됨): 여러 번 가능, 모양 검사에는 포함, 패턴 밖에서는 불가(토큰 `QUESTION`).
  - `expr`
  - 줄바꿈이나 `;`로 구분한다.
- **조건식 (결정됨)**: `if c then a else b`는 식이다. 우선순위가 가장 낮고(else 뒤는 끝까지), `else` 필수, `else if`로 연결. 조건은 Bool 스칼라, 고른 가지만 평가, 결과는 두 가지의 공통 타입(`TypeRules.conditional`: 모양 일치, 대수 ∨, 해상도 ⊔). `if`/`then`/`else` 뒤와 `then`/`else` 앞의 줄바꿈은 이어 쓰기.
  - **반환 규칙 (결정됨)**: 식 하나로 된 프로그램은 그 식이 반환값이다. 그 외에는 마지막 문장이 `return`이어야 하고, `return`은 마지막에만 올 수 있다. 문법이 아니라 `StructureChecker`가 파싱 직후 검사한다.
- **식 우선순위** (높음 → 낮음):
  1. 기본 항
  2. 후위 연산: 메서드 호출 `.ID(args)`, 멤버 `.ID`, 인덱스 `[...]`, `'`(켤레 전치), `.'`(전치)
  3. `^` `.^` (오른쪽 결합)
  4. 단항 `-`, `+`
  5. `*` `/` `//` `///` `%%` `%%%` `.*` `./`
  6. `+` `-` `.+` `.-`
  7. 범위 `a:b` (끝 미포함 [a, b)), 첨자 안에서는 분할점 체인 `a:b:c…`
- **기본 항**:
  - 숫자, `true`, `false`
  - `ID(args)` 호출 (내장 함수, 정적 임포트)
  - `ID`
  - `(expr)` 괄호
  - `(a, b, c)` 튜플: **쌓기** (결정됨, 2026-11-17 변경). 항목 모양이 모두 `𝐝`로 같으면 `Array(k, 𝐝)`, 항목 i는 `T[i, …]`. 스칼라면 열벡터(= `[a; b; c]`), 길이 M 벡터면 `Mat(k, M)`(항목이 행). 성분 객체(`Pair`, `record`)와 같은 규칙. 모양이 다른 항목은 섞을 수 없다(블록 연결은 행렬 리터럴로).
  - `[a, b; c, d]` 행렬 리터럴: 행 우선이며 `[a, b, c]`는 1×3 행벡터. 원소가 행렬이면 블록 행렬이다(`[A, B; C, D]`).
- **범위 (결정됨, 2026-11-17 변경)**: `a:b`는 **Python식 끝 미포함** [a, b)다(이전의 MATLAB식 끝 포함에서 바꿈). 증분 형태는 없다. 경계는 서수(0 이상의 정수)이며 `end`는 그 차원의 길이. VEL은 MATLAB과 다른 표기를 쓰며 혼동 가능성은 고려하지 않는다(결정됨).
- **분할 (결정됨)**: 첨자 자리의 분할점 체인 `a₀:a₁:…:aₖ`(3개 이상)은 차원을 블록 [aᵢ, aᵢ₊₁)로 나눈다. `M[a, b]`의 타입은 블록을 쌓은 블록 텐서로, 블록 번호가 앞 차원이다: 행·열 분할이면 `Array(k, l, h, w)`(블록 (i,j) = `T[i, j, :, :]`), 행만 분할이면 `Array(k, h, w)`(블록 i = `T[i, :, :]`). 블록 크기가 다르면 h/w는 ⊤. 블록 분해 `[A, B; C, D] = M[0:2:3, 0:1:3]`는 블록 단위로 묶고, 오른쪽이 분할 인덱싱 식이면 각 대상은 정확한 블록 크기를 받는다(`TypeRules.blocks`). 체인은 첨자 밖에서 불가(`StructureChecker`), 분할 인덱스에 대입 불가. 리터럴 분할점은 순증가·크기 이내 검사. (블록 번호를 앞에 두는 순서는 튜플 쌓기와 맞추려고 정함: 그래야 3차원 값이 블록 텐서인지 쌓은 값인지 구분할 필요가 없다)
- **구조 분해 규칙 (일반화)**: 값을 앞 차원부터 나눈다. 튜플 패턴(k개)은 첫 차원 = k, 각 대상은 나머지 차원(`TypeRules.destructureTuple`). 행렬 패턴(r×c)은 앞의 두 차원 = r×c, 각 대상은 나머지 차원(`destructureMatrix`). 원소 단위 분해, 블록 분해, `Pair` 분해가 모두 이 규칙.
- **인덱스**: `[i, j]`의 각 자리에 정수 스칼라, 범위(`1:3` = [1, 3)), 분할점(`0:2:3`), 또는 `:`(차원 전체)를 쓴다. 인덱스는 0부터 시작한다. 벡터는 첨자 하나로 인덱싱할 수 있다.
- **렉서**:
  - 줄바꿈은 문장 구분자다. 단, 다음 경우에는 무시한다.
    - `(`, `[` 안
    - 이항 연산자, 대입 연산자, `,`, `:`, `.` 뒤 (다음 줄로 이어 씀)
  - 주석은 `#`(줄 주석)과 `/* */`. `//`, `///`는 몫 연산자라 주석이 아니다. 여러 줄에 걸친 블록 주석은 줄바꿈 하나로 취급한다.
  - 식별자는 Java 식별자와 같은 문자 집합이다(유니코드 문자, `$`, `_` 포함). C 방식에서 Java 지역 변수를 이름 그대로 참조하기 위해서다.
  - 숫자의 `.` 뒤에는 숫자가 반드시 와야 한다(`2.`는 불가). 그래서 `2.*x`는 `2 .* x`로 읽힌다.
  - 예약어는 `return`, `true`, `false`, `if`, `then`, `else`. `then`만 Java/Kotlin 예약어가 아니므로 `then`이라는 변수는 `` `then` ``으로 쓴다. 그 밖의 Java 식별자는 모두 그대로 참조할 수 있다.
  - `end`(인덱스 안에서 그 차원의 길이)와 `j`(허수 단위)는 예약어가 아니라 **미리 선언된 변수**다. 대입하면 다시 묶인다(예: `j = 3`).
  - `o+`(XOR)는 식별자 `o` 바로 뒤에 `+`가 붙으면 그쪽으로 읽힌다(최장 일치). 변수 `o`에 더할 때는 `o + 1`처럼 띄운다.
  - 모든 연산자에 이름 붙은 토큰이 있다(`PLUS`, `DOT_STAR` 등). 렉서 액션이 직전 토큰을 보고 줄 이어짐을 판단한다.
- **연산자 의미 (결정됨, MATLAB 방식)**:
  - `*` `/` `^`: 행렬 곱, 우측 나눗셈, 행렬 거듭제곱. 피연산자가 스칼라면 일반 산술이다(`/`는 참 나눗셈).
  - `/>`: 우측 나눗셈 `A·B⁻¹`(`/`와 같음), `/<`: 좌측 나눗셈 `A⁻¹·B`. 제수는 정사각(가역) 행렬만 허용한다. (백슬래시는 Java 문자열 이스케이프와 겹쳐서 쓰지 않는다.)
  - `^`의 지수는 스칼라만. 행렬 지수 함수는 이후 `exp(A)`로 제공한다.
  - Bool: `+`는 OR, `*`는 AND, `o+`(⊕)는 XOR(덧셈 우선순위).
  - 전치는 랭크 3 이상에서 인덱스 순서를 뒤집는다.
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
  - `end`: 인덱스 안에서 해당 차원의 **길이** (결정됨, 2026-11-17 변경: 끝 미포함 범위에 맞춰 "마지막 인덱스"에서 바꿈). `v[1:end]`는 끝까지, 마지막 원소는 `v[end-1]`, 분할의 끝 점은 `end`(`M[0:2:end, :]`).
  - **이름 충돌 (결정됨)**: 미리 선언된 이름이 같은 이름의 Java 변수보다 우선한다. Java 변수는 Kotlin처럼 백틱으로 쓴다(`` `j` ``, `` `end` ``). 백틱 안에는 Java 식별자만 올 수 있다(토큰 `QUOTED_ID`). C 방식에서 루프 변수 `j`를 쓰려면 `` `j` ``로 써야 한다는 점에 유의.

## 5. 타입 시스템 결정 사항
원본: `docs/type-system.md`, `docs/type-mapping/java.md`

- **용어 (결정됨, 2026-11-18 확립)**: 문서·코드·대화 모두 이 용어를 쓴다.
  - **대수**(algebra): 해상도 없는 수학적 구조. 스칼라 대수와 그 배열. 예: `Real`, `Vec(3, Real)`, `Mat(m, n, Complex)`
  - **스칼라 대수**: 원자적인 대수 `Bool`, `Int`, `Real`, `Complex`. 코드의 `ScalarAlgebra` enum.
  - **해상도**: `Zn`, `Z+n`, `Z∞`, `Fn`, `Dn`, `D∞`. 코드의 `concretization.Resolution`.
  - **스칼라 타입**: 스칼라 대수에 해상도를 적용한 타입. 예: `⟨Real⟩_F64`(`double`), `⟨Bool⟩`. 배열 타입의 원소는 스칼라 타입이다. 코드의 `concretization.ScalarType`.
  - **타입**: 대수에 해상도를 적용한 것 `⟨K⟩_R`. 예: `⟨Real⟩_F64`(`double`), `⟨Vec(⊤, Real)⟩_F64`(`double[]`). **Java/Kotlin 타입과 대응하는 것은 타입**이다. 코드의 `concretization.ScalarType`(스칼라 대수+해상도). 배열 타입 클래스는 아직 없다(매핑 구현 때 추가). VEL 안의 값은 `type.ArrayAlgebra`(모양+스칼라 대수+상수값).
  - 예전 문서의 "스칼라 타입(대수)", "대수 = 스칼라 타입" 같은 표현은 쓰지 않는다.
- **대수의 구성**:
  - 모든 객체는 함수다.
  - `Array(d…, K)`(K는 스칼라 대수)는 0부터 시작하는 인덱스를 스칼라로 대응시키는 함수다.
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
- **`Card`를 스칼라 대수로** (결정됨 2026-11-18): `Card := 0 | ℕ | ⊤ | ⊥`. `0 < 1 < … < ⊤`이고 ⊥(무효 원소)는 순서 밖, 유효하지 않은 인덱스. ⊥는 서수의 **null**과 같은 역할(결정됨 2026-11-18): 결과가 서수가 될 수 없는 연산은 ⊥(예: m > n이면 n − m = ⊥), ⊥를 인덱스·크기·연산에 쓰면 null 원소처럼 `NullPointerException`(컴파일 타임에 알면 컴파일 오류). Card의 해상도는 Bool처럼 하나로 고정(`⟨Card⟩`, 결정됨 2026-11-18). Card는 Int의 하위 대수가 아니다: 인덱스 자리(인덱스·범위 경계·분할점)에서 Int → Card(음수는 ⊥), 산술에서 Card → Int. 범위 `a:b`의 길이는 `b − a`이고 b < a면 ⊥(null), 리터럴로 알면 컴파일 오류, 빈 범위는 `a:a`(이전의 `max(0, b − a)`에서 변경). Java에서 `⟨Card⟩`는 `int`, 산술에서 Z32. (모두 결정됨 2026-11-18) 코드: `Card.BOTTOM`, `ScalarAlgebra.CARD`, `Resolution.CARD`.
- **`Nat` 스칼라 대수는 기각** (2026-11-18): 인덱스 전용 자연수 대수는 두지 않는다. 인덱스를 `Nat`로 제한하면 `a[b[i]]`(`b: int[]`)나 `end - 1` 같은 `Int` 식을 인덱스로 못 쓰므로, 인덱스는 `Int` 스칼라로 받고 음수·범위는 실행 시 검사한다. `end`, `size` 같은 크기 값은 `⟨Int⟩` 스칼라.
- **스칼라 대수**: `Bool`, `Int <: Real <: Complex`. Bool은 다른 대수와 관계없다(`+`가 OR이라 0/1이 아님). (2026-11-18) 용어 "체(field)"는 Java 필드와 헷갈리고 Bool·Int·Vec에 수학적으로도 맞지 않아 **"대수(algebra)"**로 바꿨다. 코드는 `ScalarAlgebra` enum.
  - **Int (결정됨 2026-11-18)**: 스칼라 대수 ↔ 해상도 조합은 `Int` ↔ `Zn`/`Z+n`/`Z∞`, `Real`/`Complex` ↔ `Fn`/`Dn`/`D∞`, `Bool` ↔ `Bool`뿐이다. 미정 리터럴은 수치 대수 어디에나 붙지만 소수·유리 리터럴은 `Int`에 못 붙는다. 정수 리터럴은 `Int`, 소수 리터럴은 `Real`.
  - 정규화(`concretization.ScalarType.of`, `java.md` 7.3 "대수와 해상도 맞추기"): 결과 대수가 `Real`/`Complex`인데 해상도가 정수면 `Zn → F64`, `Z∞ → D∞`. 결과 해상도가 정수가 아닌데 대수가 `Int`면 대수를 `Real`로. 예: `Int / Int`는 `⟨Real⟩_F64`, `j * int`는 `⟨Complex⟩_F64`.
  - 인덱스와 범위 경계는 `Int` 스칼라여야 한다(`TypeRules.requireCard`).
  - 복소 정수(`ComplexInt`)는 지금은 다루지 않는다. 필요하면 나중에 추가.
- **해상도**: `Bool`, `Zn`, `Z+n`, `Z∞`, `Fn`, `Dn`(IEEE 754 십진 부동소수), `D∞`
- **해상도의 역할 재정의 (결정됨 2026-11-18)**: 해상도끼리의 변환·승격 규칙은 없다. 해상도는 Java/Kotlin 경계의 **캐스팅 규칙을 나타내는 기호**다. 흐름: Java 타입(해상도 지정) → VEL 대수 → 처리(대수 위에서) → VEL 대수 → Java 타입(대상 해상도로 캐스팅). **추상화**(abstraction) = 타입 → 대수, **구체화**(concretization) = 대수 → 타입(`type-system.md` 0.3). VEL 안의 연산은 값을 계산하지 않고 식의 연산·기호만 지시한다. 실제 계산은 구체화에서 입출력 해상도의 범위, 오버플로, 캐스팅, 유효 자릿수 규칙을 따른다. `Real → Z32` 같은 변환은 구체화의 일부이며 추상화 전 입력 해상도에 따라 다르다(예: F64 입력이면 Java `double → int` 캐스팅). 구체화는 전처리 시점에 일어나며 스칼라 펼치기, SIMD 등 최적 코드를 생성해야 한다. 중간 계산의 해상도는 **언어별 구체화 규칙**으로 옮겼다(결정됨 2026-11-18): `type-system.md` 3장은 이제 대수 위의 연산(∨, 연산자별 대수 규칙, 리터럴 = 정확한 수와 상수 접기, 식 재배열)만 다루고, 아래 승격 규칙은 `java.md` 7절 "구체화 규칙"(7.1 구체화 지점: return/대입/메서드 인자/인덱스 `int`/조건 `boolean`, 7.2 ⊑, 7.3 ⊔·최소 연산 폭·연산자별 예외·대수-해상도 맞추기, 7.4 오버플로·반올림, 7.5 D∞, 7.6 리터럴, 7.7 배열)로 옮겼다. Kotlin은 Java 7절을 따른다. **식 재배열**: 구체화할 때 결합법칙이 성립하는 연산(+, 스칼라·행렬 *, .*, Bool o+)은 묶는 순서를 바꿀 수 있다(예: `(A*B)*v → A*(B*v)`). 코드도 분리했다(2026-11-18): `core.type`은 대수만, `core.concretization`은 해상도만 다룬다.
- **승격 규칙** (`java.md` 7절 구체화 규칙으로 이동):
  - `⊑`(정확히 표현 가능): 암시적 변환은 이 방향으로만 허용한다. `F`와 `Dn` 사이는 서로 `⊑`가 아니다.
  - `⊔` 우선순위 표:
    - ∞ 정밀도 > 십진 부동소수 `Dn` > 이진 부동소수 `Fn` > 정수
    - 정수와 부동소수가 섞이면 부동소수 쪽을 따른다 (Java와 같음).
    - `Dn`도 `Fn`처럼 더 높은 너비로 승격한다. `F`와 섞이면 `D[max 너비]`다. 예: `D32 ⊔ F64 = D64`
  - 정수 연산 결과는 최소 32비트다 (Java와 같음).
  - 정수 `/` 정수는 참 나눗셈으로 `F64`다. `Z∞`가 포함되면 `D∞`다.
  - `x^(음이 아닌 정수 리터럴)`은 정수를 유지한다. 그 외의 지수 `y`는 `x ⊔ y ⊔ F64`다 (결정됨: 지수의 해상도도 반영).
  - **정수와 부동소수가 섞이면 부동소수로 승격한다 (결정됨, 규칙 4·5).** 정수 너비와 관계없다(`Z64 ⊔ F32 = F32`). 아래 중간 해상도 정책도 이 규칙을 바꾸지 않는다.
  - **중간 연산은 해상도를 최대한 높게 유지한다 (결정됨).** 리터럴끼리의 연산은 정확히 상수 접기한다(`1/3`은 분수 리터럴). VEL 밖으로 나갈 때(return, Java/Kotlin 변수) 대상 해상도가 낮으면 손실을 허용하고 변환한다. 이후 `@VelConfig`(fastmath)로 바꿀 수 있게 한다.
  - 반올림·부동소수 오버플로는 IEEE 754 기본(roundTiesToEven, ±∞).
  - 인덱스 대입: 값은 선택 영역에 대입 가능해야 하고(모양은 같거나 브로드캐스팅), 해상도가 더 크면 배열 전체가 커진 해상도를 따른다.
  - Complex의 몫·나머지는 정의하지 않는다(함수·사용자 정의 중위 연산자 논의 때 정의).
  - 고정 폭 정수 오버플로는 순환(wrap)한다.
  - 리터럴은 미정 해상도로 상대 피연산자를 따른다. 기본값은 `Z32`와 `F64`다.
  - **`D∞` 정밀도**: 중간 연산은 반올림 오차를 누적하지 않고, 최종 결과에서만 필요할 때 반올림한다.
    - `+ - ×`는 정확히 계산한다.
    - `/`는 분수 `p/q`로 지연해 최종 결과에서 한 번만 나눈다.
    - 무리 연산만 작업 정밀도로 중간 반올림한다.
    - 최종 결과 = 값이 VEL 밖으로 나가는 시점(return, Java 변수 대입, 외부 함수 인자, 해상도 변환). 나누어떨어지면 반올림 없이 정확한 값을 낸다.
    - Java 기본값: 최종 정밀도 `MathContext.DECIMAL128`, 작업 정밀도는 그 2배(68자리). 둘 다 추후 `@VelConfig`로 변경 가능.
- **Java 매핑**:
  - 원시 타입과 래퍼 타입은 위 해상도에 대응한다. `char`는 `Z+16`이다. 래퍼가 `null`이면 NPE.
  - `BigInteger`는 `Z∞`, `BigDecimal`은 `D∞`다. `Dn`은 Java 타입이 없어서 `BigDecimal` + `MathContext.DECIMALn`으로 표현한다.
  - 시퀀스는 **배열과 `List`로 한정**한다.
  - **성분 객체 (결정됨)**: Java `record`와 `component1()`…`componentN()`이 있는 클래스(Kotlin `Pair`, `Triple`, data class)는 성분 대수의 모양이 같고 스칼라 대수에 상한 `K = K₁∨…∨Kₙ`이 있으면 `⟨Array(N, 𝐝, K)⟩_{R₁⊔…⊔Rₙ}`다(예: `Pair<Int, Double>` → `⟨Vec(2, Real)⟩_F64`, `Pair<Int, Boolean>`은 배열 아님)(성분 모양 앞에 N 차원을 붙임). 스칼라 성분은 `Vec(N)`, 길이 M 벡터 성분은 `Mat(N, M)`. 해상도가 다르면 넓은 쪽. 어디서나 배열로 쓴다. VEL 값이 이 타입으로 나갈 때는 성분별로 나눠 생성자(record 표준 생성자, Kotlin 주 생성자)로 감싼다. (`java.md` 2.5·5절, `kotlin.md` 3절)
  - `F[n]`은 `Vec(n)`이다.
  - `F[m][n]`은 `Mat(n,m)`이다. 차원을 역순으로 두는 **열 우선** 방식이며, `a[i][j]`는 `(j, i)`에 대응한다.
  - 크기는 기본 `⊤`이고, `@Dim`으로 지정한다. `@Dim(...)`(`value`) = `@Dim(vel = {...})`(VEL 순서, 행 먼저), `@Dim(java = {...})`(Java 선언 순서) 중 정확히 하나를 쓴다. 둘 이상이면 `VelTypeException`(`DimSpec.fromAnnotation`). 지정한 크기는 검사하지 않고 신뢰한다.
  - 범위 밖 접근(`ArrayIndexOutOfBoundsException` 등)은 NPE로 보고한다.
  - jagged 배열: 안쪽 차원을 `⊤`로 보고 같은 규칙(범위 밖은 null → NPE)을 따른다.

### 타입 모델 구현 메모 (`vel-core` `core.type`, `core.concretization`)
- `ArrayAlgebra`는 차원을 정규형으로 둔다: 랭크는 최소 2(`Array(n)` = `Vec(n)` = `Mat(n,1)`), 2번째 이후 끝의 크기 1 차원은 지운다. 그래서 문서의 정의가 `equals`로 성립한다.
- 리터럴과 상수 접기 결과는 스칼라 `ArrayAlgebra`의 `constant`(`Constant.Int`/`Decimal`/`Rational`, 소수는 끝의 0을 정리한 정규형)로 들고 다닌다. 상수 접기는 `Constant.fold`(VEL 의미론). 리터럴 인덱스, 범위 길이, `^` 규칙이 이 값을 본다. 단항 `-`는 상수를 상수로 유지한다(`x * -1`이 `x`를 따르게).
- 구체화 쪽에서 상수는 `Operand.Literal`, 해상도가 정해진 값은 `Operand.Fixed`. `Promotion.adopt`로 상수가 상대 해상도를 따른다.
- `j`는 VEL에서 `Complex` 스칼라(상수값 없음). 구체화할 때 실수부·허수부를 정수 상수로 보고 상대 해상도를 따르게 한다(구체화기 구현 때 반영).
- `Promotion.join`(⊔)은 최소 연산 폭을 적용하지 않는다(연결용). 산술 결과(`Promotion.arithmetic`)에만 적용한다.
- 인덱싱 규칙(`TypeRules.index`): 첨자 수 = 랭크, 단 벡터는 첨자 하나. 정수 스칼라 첨자는 크기 1, 범위는 그 길이, `:`는 차원 전체. 리터럴 인덱스가 정해진 크기를 넘으면 오류. (제가 정한 부분: 벡터의 단일 첨자 규칙, 리터럴 인덱스 범위 검사)
- `A ^ B`: 지수가 음이 아닌 정수 상수면 대수는 `A`(반복 곱), 아니면 `A ∨ B ∨ Real`. 해상도는 각각 `A`, `A ⊔ B ⊔ F64`(지수의 해상도도 반영).
- **원소별 적용 (결정됨 2026-11-18)**: Julia식 `f.(x)`, 메서드는 `t.foo.(x)`, `Math.sqrt.(v)`. `f(A)`(행렬 함수, 예: `exp(A)` 행렬 지수)와 `f.(A)`(원소별)를 구분하고, 스칼라 함수에 배열을 넘기는 `f(v)`는 타입 오류. 여러 인자는 1.3절 브로드캐스팅. 구체화 때 이어진 원소별 연산은 루프 하나로 합칠 수 있다. 문법: `primary`의 `broadcastCallExpr`, `expr`의 `broadcastMethodCallExpr`(`type-system.md` 1.9, `vel-grammar.md` 4.2). 타입 규칙 코드는 함수 대수와 함께 추가.
- **오버로딩 해소 (결정됨 2026-11-18)**: Java 시그니처 = 이름 + 매개변수 타입(반환 타입 제외, 제네릭은 소거). VEL의 **시그니처 지정** `Math.abs{double}`, `Math.max{int, int}`, `v.norm{}`: 중괄호 안은 **타깃 언어의 타입**(Java `double`, Kotlin `Double`), 동적 메서드는 수신 객체 제외. 호출(`f{double}(x)`), 원소별 적용(`f{double}.(v)`), 함수 값(`abs = Math.abs{double}` → `⟨Func(Real, Real)⟩_F64`)에 쓴다. 시그니처 없는 호출은 JLS 15.12(가장 구체적인 후보)를 구체화 때 중간 해상도로 적용(`Math.abs(-2)`는 Z32라 `abs(int)`). 시그니처 없는 참조는 오버로딩이 하나일 때만, 여럿이면 컴파일 오류. 내장 함수 이름(`abs`)도 대입하면 가려진다. 문법: `signature`, `typeName`, `methodRefExpr`, `functionRef`(`java.md` 3.1, `vel-grammar.md` 4.3). Kotlin 제네릭 타입 인자 표기는 미정.
- 함수 대수는 아직 모델에 없다(`VelAlgebra`는 `ArrayAlgebra`만 허용). 함수 문법과 함께 추가한다. 표기는 `Func(A, B)`(결정됨 2026-11-18, `A ↦ B` 대신). `Func(A, B)`는 대수이고 A, B도 대수다. 구성자(`Array`, `Func`, `×`)의 인자 자리에 대수 대신 **해상도**나 **타입**을 넣을 수 있다(결정됨 2026-11-18; 입력/출력 해상도 분리안은 취소). 인자가 모두 해상도면 구조를 가진 해상도(`Func(F64, Z64)`, `F64 × Z32`), 모두 타입이면 타입. `⟨Func(A, B)⟩_{Func(R_A, R_B)} = Func(⟨A⟩_{R_A}, ⟨B⟩_{R_B})`, 단일 `R`은 모든 자리에 적용. 예: `DoubleToLongFunction` = `⟨Func(Real, Int)⟩_{Func(F64, Z64)}`, `Math.scalb` = `⟨Func(Real × Int, Real)⟩_{Func(F64 × Z32, F64)}`. 배열의 인덱스 집합에는 해상도가 없다. **배열의 해상도는 하나**이고, 해상도가 다른 원소로 만들면(성분 객체 등) 기본으로 가장 큰 해상도(언어별 ⊔, `java.md` 7.3)를 고른다(결정됨 2026-11-18). 해상도 연산 규칙은 구체화 규칙(`java.md` 7)으로 옮겨 정리했다.

## 6. 미정 / 추후 과제

- [ ] Kotlin 컴파일러 플러그인의 한계
  - IR 단계라서 오류가 IDE 편집기에는 표시되지 않고 빌드할 때만 나온다. IDE에 표시하려면 FIR 체커(`FirAdditionalCheckersExtension`)가 필요하다.
  - 컴파일러 플러그인 API는 Kotlin 버전마다 바뀌므로, Kotlin을 올릴 때 `vel-kotlinc`를 함께 확인해야 한다.
  - Gradle 사용자를 위한 Gradle 플러그인(`KotlinCompilerPluginSupportPlugin`)은 아직 없다. 지금은 `kotlinCompilerPluginClasspathMain`에 직접 추가한다.
- [ ] Kotlin 문자열 템플릿: `@Vel("...")` 안의 `$`는 Kotlin 템플릿으로 해석된다. VEL 식별자에 `$`가 있으면 `${'$'}`로 써야 한다.
- [ ] Kotlin 고유 기능의 매핑: `operator fun`을 정의한 사용자 타입, 기본값·이름 있는 인자, `IntRange` 등

- [ ] `@VelConfig` 설계: 최종 정밀도 `MathContext`, 작업 정밀도 등. 형식과 적용 범위(클래스, 메서드, 패키지)
- [ ] **함수 문법**: 사용자가 추후 직접 설계할 예정. 오버로딩, 가변 인자, 제네릭, `void` 처리, `complex(a, b)` 같은 내장 함수
- [ ] 그 밖의 제어문(반복 등)과 성분별 조건 선택(Bool 배열 조건)
- [ ] 비교·논리 연산: Bool 리터럴은 생겼지만 Bool 값을 만드는 연산자가 없다.
- [ ] 명시적 변환(cast)과 타입 표기 문법: `⊑`를 거스르는 대입에 필요
- [ ] 문법 구현 정리
  - `NUMBER`를 `INT`/`DECIMAL`로 분리(리터럴 해상도 규칙용)
  - 오류 메시지 개선: 인라인에 문장을 쓴 경우, 닫히지 않은 괄호
- [ ] 의미 분석에서 거부할 것: 인덱스 밖의 `:`·`end`. (`@Dim` 중복은 `DimSpec`, 첨자 밖의 분할점 체인과 `return` 규칙은 `StructureChecker`, 행 길이 불일치는 연결 규칙이 처리함)
- [ ] 비교·논리 연산의 연산자와 타입 규칙
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
  - 대수 변수는 `\mathbb` (예: $\mathbb K$)
  - **해상도 기호 (2026-11-18 변경)**: 종류와 비트 수를 묶은 하나의 기호로 쓴다: `Zn`, `Z+n`(LaTeX `\mathrm{Z}^{+}n`), `Z∞`, `Fn`, `Dn`, `D∞`. LaTeX에서는 `\mathrm{F}64`, 타입에 붙이면 `⟨Real⟩_{\mathrm{F}64}`. 비트 수가 식이면 대괄호(`Z[max(a, b) + 1]`). 함수 호출처럼 읽히는 `F(64)`, `⟨K⟩_F(64)`는 쓰지 않으며, 줄임 표기 구분도 없앴다. 코드의 `Resolution.toString()`도 같은 기호(`F64`, `Z+16`, `D∞`)를 쓴다.
  - 타입 표기 `⟨K⟩_R`의 K는 스칼라 대수뿐 아니라 배열 대수도 된다: `⟨Array(𝐝, K)⟩_R = Array(𝐝, ⟨K⟩_R)`. 예: `⟨Vec(3, Real)⟩_F64`. `Vec(2, F64)`는 타입이 아니라 구조를 가진 해상도다(위 함수 항목 참고). 해상도 없이 쓴 `Vec(3, Real)`은 타입이 아니라 대수이므로, Java/Kotlin 타입과 짝짓는 표에는 항상 해상도까지 쓴 타입을 적는다.
- 줄바꿈은 LF로 고정한다(`.gitattributes`). 예외로 `*.bat`, `*.cmd`는 CRLF.
- 커밋은 사용자가 요청할 때만 한다. 원격은 `origin` = https://github.com/DoubleDeltas/Vel.git, 작업 브랜치는 `master`.
