# VEL 프로젝트 작업 맥락

에이전트(및 사람)가 작업을 이어받을 때 읽는 요약. 세부 명세는 `docs/`가 원본이며, 이 문서는 **결정 사항과 그 이유, 진행 상태, 남은 과제**를 기록한다.

마지막 갱신: 2026-11-18 (구체화기·경계 변환·표현 둘 반영, 진행 상태 정리)

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
| `vel-runtime` | `@Vel`(SOURCE 유지), `@Dim(java=…|vel=…)`(CLASS 유지, 배열 크기 지정), `VelInline.vel(String)`(플러그인 미적용 시 예외를 던지는 자리표시자), 생성 코드가 부르는 `VelMath`, `VelException`. 사용자 런타임 클래스패스에 올라가는 유일한 모듈 |
| `vel-core` | `Vel.g4`, `VelFrontend.parseProgram/parseExpression`, `ParseResult`, `VelDiagnostic`. VEL 대수 모델은 `core.type` 패키지(`Card`, `ScalarAlgebra`, `ArrayAlgebra`, `Constant`, `Arithmetic`, `Shapes`, `BinaryOperator`, `TypeRules`), 구체화 규칙은 `core.concretization` 패키지(`Resolution`, `ScalarType`, `Operand`, `Promotion`), 코드 생성은 `core.codegen`(`CodeGenerator`, `Code`, `Boundary`, `ConcreteType`, `JavaPrinter`, `KotlinPrinter`, `JavaBoundary`, `KotlinBoundary`, `ModelBoundaries`, `JavaCodeGenerator`) |
| `vel-processor` | `VelProcessor`: `@Vel` 메서드를 검증하고 `<Owner>Vel` Java 구현 클래스를 생성. 오류는 어노테이션 값 위치에 보고 |
| `vel-javac` | `VelJavacPlugin`: ANALYZE 시작 시 `VelInline.vel` 호출을 생성 코드(switch 식)로 치환. `autoStart=true` |
| `vel-examples` | B와 C 사용 예. `annotationProcessor`로 processor와 javac 모듈을 연결 |
| `vel-ksp` | `VelSymbolProcessor`(Kotlin): `@Vel` 함수를 검증하고 `<Owner>Vel` Kotlin 구현 클래스를 생성. `vel-processor`의 Kotlin 대응 |
| `vel-kotlinc` | Kotlin 컴파일러 플러그인: IR 단계에서 `VelInline.vel` 호출(`CallableId`로 판별)을 `IrEmitter`가 만든 IR 블록으로 치환. 오류는 `IrDiagnosticReporter`로 `VEL_ERROR`/`VEL_WARNING` 진단을 보고 |
| `vel-examples-kotlin` | Kotlin B·C 사용 예. `ksp project(':vel-ksp')`, `kotlinCompilerPluginClasspathMain project(':vel-kotlinc')`로 연결 |

- **빌드 메모**:
  - antlr 플러그인이 ANTLR 도구 전체를 `api` 구성으로 흘려보내는 문제는 막아 두었다. 하위 모듈에는 `antlr4-runtime`만 전파된다.
  - 패키지는 `packageName` 속성으로 지정한다. `-package` 인자는 Gradle 9에서 deprecated다.
- **진행 상태 (2026-11-18)**: 스칼라·배열 식의 구체화기가 Java B/C, Kotlin B/C 네 경로 모두에서 동작한다(타입 검사는 생성기 안에서 `TypeRules`로). 래퍼·`List`·객체 배열·성분 객체·nullable·부호 없는 정수 경계 변환 포함. 미지원 목록은 아래 "Java 구체화기 구현 메모"와 6절. 테스트는 core 98(문법 38, 승격 14, 타입 규칙 29, 생성기 17), processor 8, javac 10, ksp 10, kotlinc 11, examples 2, examples-kotlin 2개이며 모두 통과한다.

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
  - **타입**: 대수에 해상도를 적용한 것 `⟨K⟩_R`. 예: `⟨Real⟩_F64`(`double`), `⟨Vec(⊤, Real)⟩_F64`(`double[]`). **Java/Kotlin 타입과 대응하는 것은 타입**이다. 코드의 `concretization.ScalarType`(스칼라 대수+해상도), 배열까지는 `codegen.ConcreteType`(모양+스칼라 타입+표현). VEL 안의 값은 `type.ArrayAlgebra`(모양+스칼라 대수+상수값).
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

### Java 구체화기 구현 메모 (`vel-core` `core.codegen`, 2026-11-18 1차)
- `JavaCodeGenerator.methodBody(program, params, returnType)`: 파스 트리를 직접 따라가며 대수(`TypeRules`)와 해상도(`Promotion`)를 정하고 Java 문장을 낸다. 결과는 메서드 본문 문자열. 오류는 위치가 붙은 `VelCompileError`(줄 1부터, 열 0부터).
- `ConcreteType`(모양 + `ScalarType`)이 경계의 타입이다. Java 배열은 java.md 2절대로 VEL 차원 k = 안쪽에서 k번째 배열 단계, 끝의 크기 1 차원은 표현하지 않는다(`Vec(n)` = `T[]`, `Mat(m, n)` = `T[n][m]`, `Mat(1, n)` = `T[n][1]`). `JavaTypes`: 해상도 → Java 원시 타입(가장 좁은 것; `Z+16`은 `char`, 그 밖의 `Z+n`은 부호 있는 n+1비트).
- 값 모델(`Val`): 대수, 원소 해상도(상수·Not이면 null), Java 코드(스칼라는 식, 배열은 변수 이름), `external`(Java 소유 배열이라 밖으로 나갈 때 복사).
- 스칼라 연산은 피연산자를 결과 해상도로 캐스트해 계산한다(`Int / Int` → `(double) a / (double) b`). 몫·나머지는 `Math.floorDiv/floorMod`, 실수는 `VelMath`. `^`는 음이 아닌 정수 상수면 `VelMath.pow`, 아니면 `Math.pow`. Bool `+ * o+`는 `|| && ^`.
- 상수는 Java 변수를 만들지 않고 상수로 남는다(`k = 2` 뒤 `k * x`도 `x`를 따른다). 리터럴은 상대 해상도의 Java 리터럴로 낸다(`0.5f`, `2L`, 분수는 34자리 십진).
- 배열 연산은 반복문(바깥 루프 = 마지막 VEL 차원): 원소별 연산과 브로드캐스팅(⊤ 길이는 `Math.max`), 행렬 곱, 전치, 행렬 리터럴(블록 연결), 튜플 쌓기, 범위 값, 인덱싱(`end`, 범위, `:`, 벡터 단일 첨자), 인덱스 대입(항상 복사 후 쓰기), 구조 분해.
- 조건식은 가지에 문장이 필요하면 `if/else` 블록으로 내서 고른 가지만 평가한다. `error()`는 `VelMath.<T>error()`, `return error()`는 `throw new VelException()`.
- 범위 밖·음수 인덱스·음수 길이(⊥)는 본문을 `try { … } catch (IndexOutOfBoundsException | NegativeArraySizeException e) { throw VelMath.outOfRange(e); }`로 감싸 `NullPointerException`으로 바꾼다(java.md 2.2).
- 아직 지원하지 않음(컴파일 오류로 보고): 함수·메서드 호출(`error()` 제외), 멤버 접근, 함수 리터럴, Complex/`j`, 값으로서의 `inf`, Z∞·Dn·D∞, 행렬 나눗셈·행렬 거듭제곱, 분할, Bool 행렬 곱.
- `vel-runtime`의 `VelMath`: 생성 코드가 부르는 도우미(`pow`, 실수 `floorDiv/truncDiv/floorMod`, `unsignedToDouble`, `newArray`/`newBoxedArray`/`newArrayLike`, `error`, `outOfRange`).
- `VelProcessor`: `@Vel` 메서드마다 본문을 생성해 소유 타입별로 `<Owner>Vel`(중첩이면 `Outer_InnerVel`)을 만든다. 인터페이스는 `implements`, 추상 클래스는 `extends`. `@Vel`이 아닌 추상 메서드가 남으면 생성 클래스도 `abstract`. 제네릭 메서드·소유 타입은 아직 거부. 테스트는 생성 코드를 실제로 컴파일·실행한다(`JavaCodeGeneratorTest`, `VelProcessorTest`, `vel-examples`의 `PhysicsTest`).

### C 방식(`vel("...")`) 구현 메모 (`vel-javac`, 2026-11-18 1차)
- `InlineRewriter`가 `TaskEvent.Kind.ANALYZE` **시작** 시점(타입 분석 전)에 컴파일 단위마다 한 번 `vel("...")` 호출을 생성 코드로 바꾼다. 이후 javac가 생성 코드를 보통 코드처럼 분석·컴파일한다.
- 생성 코드는 `JavaCodeGenerator.inlineExpression`의 switch 식 `switch (0) { default -> { …; yield 결과; } }`. 식 자리에 반복문 등 문장을 넣기 위해서다. 생성 트리는 `ParserFactory`로 파싱하고 모든 위치를 호출 위치로 맞춘다.
- 타입 분석 전이므로 변수 타입은 선언에서 읽는다: 매개변수, 앞선 지역 변수, 필드(원시 타입·배열, `@Dim`). `var`나 다른 타입의 변수는 가려지지만 쓸 수 없다. 결과 타입은 문맥(`return`, 변수 선언, 대입)에서, 그 밖에는 값 자신의 타입(java.md 6.1).
- 호출 판별은 구문으로 한다: `import static …VelInline.vel`(또는 `.*`) 뒤의 `vel(...)`, `VelInline.vel(...)`(import 시), `com.doubledeltas.vel.VelInline.vel(...)`.
- **javac 내부 API 접근**: `JavacInternals`가 플러그인 시작 시 `jdk.compiler`의 `api/code/parser/tree/util` 패키지를 자기 모듈에 export한다(`sun.misc.Unsafe`로 `Module.implAddExports` 호출, Lombok과 같은 방식). 사용자는 `--add-exports`가 필요 없다. JDK가 바뀌면 확인 필요. `vel-javac`의 컴파일과 javadoc에는 `--add-exports`를 준다.
- 테스트: `VelJavacPluginTest`가 실제로 컴파일·실행한다. `vel-examples`의 `Physics.kineticEnergy`(인라인)도 `PhysicsTest`에서 실행된다.
- Kotlin 쪽은 아래 "Kotlin 구체화기 구현 메모" 참고.

### 공통 중간 코드와 Kotlin 구체화기 구현 메모 (2026-11-18)
- **구조 (결정됨)**: `CodeGenerator`(VEL → 대상 언어와 무관한 `Code`: JVM 원시 타입·배열 위의 작은 명령형 언어) + 백엔드 3개. `JavaPrinter`(Java 소스; B는 메서드 본문, C는 switch 식), `KotlinPrinter`(Kotlin 소스, KSP의 `@Vel`), `vel-kotlinc`의 `IrEmitter`(Kotlin IR, 인라인 `vel(...)`). `JavaCodeGenerator`는 `CodeGenerator` + `JavaPrinter`를 감싼 얇은 겉면.
- `Code`: 식 `Lit Ref Binary Neg Convert Call(Helper) Choose Fail Get Length NewArray`, 문장 `Let Var Declare Assign Store For If Return Throw`, `Body(statements, guarded)`. `guarded`면 백엔드가 범위 밖 예외를 `VelMath.outOfRange`로 바꾸는 try로 감싼다. 임시 이름은 `t__0`처럼 `__`(Java·Kotlin 모두 유효), VEL 변수가 Java/Kotlin 키워드면 `__`를 붙인다.
- **KSP(`vel-ksp`)**: Kotlin 타입(원시, 원시 배열, `Array<…>`, `@Dim`) → `ConcreteType`, `<Owner>Vel`을 Kotlin 소스로 생성(인터페이스 구현, 추상 클래스는 `Owner()` 상속, 남은 추상 멤버가 있으면 `abstract class`). Kotlin 출력: `Char`는 `.code`/`.toChar()`, `Byte`/`Short`는 실수에서 `toInt()` 경유, 2차원 이상 배열은 `Array(n) { DoubleArray(m) }`.
- **IR(`vel-kotlinc`)**: `IrGenerationExtension`에서 `VelInline.vel` 호출을 `IrEmitter`가 만든 블록으로 바꾼다. 보이는 이름은 매개변수·앞선 지역 변수·람다 매개변수(속성은 아직), 결과 타입은 호출의 추론 타입(`call.type`, 플랫폼 타입의 nullable은 무시). 산술·비교는 원시 클래스 멤버(`Double.plus`, `lessFunByOperandType`), 반복은 `while`, `Char.code`는 `kotlin` 패키지의 확장 속성.
  - Kotlin 2.4에서 확인한 함정: 선언 조회는 `finderForSource(file)`을 쓴다. Java 정적 메서드(`VelMath`, `java.lang.Math`)는 `findFunctions`로 안 잡혀서 `findClass(...).owner.declarations`에서 찾는다. `arrayOfNulls`(reified, 내장 심볼도)는 플러그인이 만든 호출에서 인라인되지 않아 런타임 오류가 나므로, 2차원 이상 배열은 `VelMath.newArray(descriptor, lengths...)`(`java.lang.reflect.Array.newInstance`) 후 `irAs`로 캐스트한다.
- 테스트: `VelSymbolProcessorTest`(생성 Kotlin을 컴파일·실행), `VelCompilerPluginTest`(인라인 IR을 컴파일·실행), `vel-examples-kotlin`의 `PhysicsTest`(Gradle에서 KSP + 플러그인).
- **nullable과 부호 없는 정수 (결정됨 2026-11-18)**: `T?`는 `T`와 같은 대수(kotlin.md 1.3). KSP는 함수 첫머리에서 `val x: T = x as T`(null이면 NPE, 안쪽 nullable 배열은 unchecked cast로 원소 접근 시 NPE), 시그니처는 선언 타입 그대로(`render`). IR은 읽을 때 `checkNotNull`. 부호 없는 정수는 kotlin.md 1.2대로 지원: `Code`에 `ubyte uint ulong`(비트 저장, `JavaTypes.storage`), `Z+16`은 `char`. 산술은 비트 그대로 순환, 나눗셈·나머지·비교·변환은 부호 없이(Java: `Integer.divideUnsigned` 등, `VelMath.unsignedToDouble`; Kotlin 소스: `UInt` 연산자 그대로; IR: `UInt` 멤버와 `kotlin.toUInt()` 등 확장 함수, 비교는 `compareTo`). `UShort`는 `ushort` 표현(아래 "해상도의 표현 둘").

### 경계 변환(Boundary) 구현 메모 (2026-11-18)
- 생성기(`CodeGenerator`)는 JVM 원시 타입·배열만 다루고, 그 밖의 값은 **경계에서 변환**한다. `Boundary`(vel-core): `Direct`(원시·원시 배열), `Boxed`(래퍼, Kotlin 중첩 nullable 스칼라), `Sequence`(LIST / ARRAY, 원소 인덱스가 마지막 VEL 차원), `Components`(record·`componentN()`, 성분 인덱스가 첫 VEL 차원, 대수 ∨·해상도 ⊔). `Boundary.withDims`로 `@Dim` 적용.
- 생성기는 경계 변수를 `name__v` 지역으로 읽고(`codeNames`), 실제로 쓴 변수만 `Body.used`에 남긴다 → 쓴 변수만 변환.
- 백엔드별: `JavaBoundary`(Java 소스, `JavaPrinter`의 prologue와 `ResultWriter`), `KotlinBoundary`(Kotlin 소스, `KotlinPrinter.functionBody(body, prologue, returned, resultBlock)`), `IrEmitter`의 `Read`/`Write`(IR: `Collection.size`, `List.get`, `componentN()`(클래스 타입 매개변수 치환), `java.util.ArrayList(Int)`+`add`, 생성자, `VelMath.newBoxedArray`).
- 타입 → Boundary: Java `@Vel`은 `ModelBoundaries`(javax.lang.model; record `@Dim`은 접근자에서 읽음). javac 플러그인은 원시 타입이 아니면 타입 트리 사본을 클래스 환경에서 `Attr.attribType`(진단은 버림)한 뒤 `ModelBoundaries`. KSP·IR은 각자 Kotlin 타입에서 만든다(`Array<원시 배열>`은 Direct, `Array<그 밖>`은 Sequence).
- `Array<UIntArray>`(IR): `VelMath.newArrayLike(prototype, n)`(값 클래스 배열은 JVM에서 박싱된 객체 배열). Kotlin 소스는 `Array(n) { UIntArray(m) }`.
- 반환 방향이 행/열로 다르면(`Mat(1, ⊤)`를 `double[]`로) 컴파일 오류: 전치하라고 안내.
- KSP 테스트: kctfork가 KSP 2.3.12 구현을 끌어와 API 2.3.14와 어긋나 속성 방문 중 `NoSuchMethodError` → 테스트 의존성에 `symbol-processing`, `-common-deps`, `-aa-embeddable` 2.3.14를 명시.
- **해상도의 표현 둘 (결정됨 2026-11-18)**: 같은 해상도에 표현이 여럿일 수 있다. `Z+16`은 기본 `char`(Java `char`, Kotlin `Char`)이고, Kotlin `UShort`/`UShortArray`에서 온 값은 `ushort`(`short` 비트). `ConcreteType.representation`과 `Boundary.Direct.representation`에 담고(`primitive()`), 생성기는 해상도가 아니라 값의 코드 타입에서 변환을 시작한다(`convTo`). 계산 결과는 기본 표현, 반환할 때 대상 표현으로 비트 변환(복사 없음, 배열은 표현이 다를 때만 복사). 이전의 `UShort` ↔ `Char` 경계 어댑터는 없앴다.
- 아직: 인라인 IR에서 스칼라가 아닌 원소의 `Array` 결과, Kotlin 인라인에서 속성 참조.

### 정적 메서드 호출 구현 메모 (2026-11-19)
- `T.foo(x)`, `java.lang.Math.sqrt(x)`, `Math.abs{double}(-2)`가 네 경로 모두에서 동작한다. 클래스 이름은 정규화된 이름이나 `java.lang`의 단순 이름. 수신 객체 자리의 이름이 VEL/타깃 변수면 클래스로 보지 않는다(인스턴스 메서드는 아직 미지원 오류).
- `MethodLookup`(core): 호스트가 클래스의 public static 메서드를 넘긴다. `ModelMethods`(javax.lang.model; processor·javac), KSP `Resolver.getClassDeclarationByName` + `FunctionKind.STATIC`, IR `finderForSource(file).findClass` + `isStatic`. 매개변수와 반환은 원시 타입과 그 배열(`Boundary.Direct`)만 VEL 타입이 되고, 나머지는 null(그 메서드를 부르면 오류).
- 해소(`CodeGenerator.mostSpecific`): JLS 15.12.2의 1단계만(동일·확장 원시 변환, 박싱·가변 인자 제외) 적용 가능한 후보 중 가장 구체적인 것. 인자 타입은 값의 표현, 상수는 기본 해상도(7.6). 하나로 안 정해지면 오류. 시그니처 `{…}`는 소거한 타입 이름으로 비교하고, 단순 이름은 마지막 부분만 비교한다(Java `double`, Kotlin `Double` 모두).
- 인자 변환: 대수 ⊑ 확인, 정수 상수는 매개변수 해상도에 들어가야 하고, 변수는 ⊑ 또는 Java 확장 변환이어야 한다(`long`을 `int` 매개변수에 넘기면 오류). 배열은 모양·방향을 확인하고 필요하면 복사.
- 중간 코드 `Code.Invoke(owner, method, params, args, type)`: 호출은 한 번 평가해 지역 변수에 담는다. 반환된 배열은 공유될 수 있어 `external`로 보고, VEL을 떠날 때 복사한다.
- 제외: 제네릭 메서드, 가변 인자 메서드, Kotlin `object`/companion 함수, 인스턴스 메서드, 메서드 참조 값, `f.(x)`.

### 타입 모델 구현 메모 (`vel-core` `core.type`, `core.concretization`)
- `ArrayAlgebra`는 차원을 정규형으로 둔다: 랭크는 최소 2(`Array(n)` = `Vec(n)` = `Mat(n,1)`), 2번째 이후 끝의 크기 1 차원은 지운다. 그래서 문서의 정의가 `equals`로 성립한다.
- 리터럴과 상수 접기 결과는 스칼라 `ArrayAlgebra`의 `constant`(`Constant.Int`/`Decimal`/`Rational`, 소수는 끝의 0을 정리한 정규형)로 들고 다닌다. 상수 접기는 `Constant.fold`(VEL 의미론). 리터럴 인덱스, 범위 길이, `^` 규칙이 이 값을 본다. 단항 `-`는 상수를 상수로 유지한다(`x * -1`이 `x`를 따르게).
- 구체화 쪽에서 상수는 `Operand.Literal`, 해상도가 정해진 값은 `Operand.Fixed`. `Promotion.adopt`로 상수가 상대 해상도를 따른다.
- `j`는 VEL에서 `Complex` 스칼라(상수값 없음). 구체화할 때 실수부·허수부를 정수 상수로 보고 상대 해상도를 따르게 한다(구체화기 구현 때 반영).
- `Promotion.join`(⊔)은 최소 연산 폭을 적용하지 않는다(연결용). 산술 결과(`Promotion.arithmetic`)에만 적용한다.
- 인덱싱 규칙(`TypeRules.index`): 첨자 수 = 랭크, 단 벡터는 첨자 하나. 정수 스칼라 첨자는 크기 1, 범위는 그 길이, `:`는 차원 전체. 리터럴 인덱스가 정해진 크기를 넘으면 오류. (제가 정한 부분: 벡터의 단일 첨자 규칙, 리터럴 인덱스 범위 검사)
- `A ^ B`: 지수가 음이 아닌 정수 상수면 대수는 `A`(반복 곱), 아니면 `A ∨ B ∨ Real`. 해상도는 각각 `A`, `A ⊔ B ⊔ F64`(지수의 해상도도 반영).
- **원소별 적용 (결정됨 2026-11-18)**: Julia식 `f.(x)`, 메서드는 `t.foo.(x)`, `Math.sqrt.(v)`. `f(A)`(행렬 함수, 예: `exp(A)` 행렬 지수)와 `f.(A)`(원소별)를 구분하고, 스칼라 함수에 배열을 넘기는 `f(v)`는 타입 오류. 여러 인자는 1.3절 브로드캐스팅. 구체화 때 이어진 원소별 연산은 루프 하나로 합칠 수 있다. 문법: `primary`의 `broadcastCallExpr`, `expr`의 `broadcastMethodCallExpr`(`type-system.md` 1.9, `vel-grammar.md` 4.2). 타입 규칙 코드는 함수 대수와 함께 추가.
- **오버로딩 해소 (결정됨 2026-11-18)**: Java 시그니처 = 이름 + 매개변수 타입(반환 타입 제외, 제네릭은 소거). VEL의 **시그니처 지정** `Math.abs{double}`, `Math.max{int, int}`, `v.norm{}`: 중괄호 안은 **타깃 언어의 타입**(Java `double`, Kotlin `Double`), 동적 메서드는 수신 객체 제외. 호출(`f{double}(x)`), 원소별 적용(`f{double}.(v)`), 함수 값(`abs = Math.abs{double}` → `⟨Func(Real, Real)⟩_F64`)에 쓴다. 시그니처 없는 호출은 JLS 15.12(가장 구체적인 후보)를 구체화 때 중간 해상도로 적용(`Math.abs(-2)`는 Z32라 `abs(int)`). 시그니처 없는 참조는 오버로딩이 하나일 때만, 여럿이면 컴파일 오류. 내장 함수 이름(`abs`)도 대입하면 가려진다. 문법: `signature`, `typeName`, `methodRefExpr`, `functionRef`(`java.md` 3.1, `vel-grammar.md` 4.3). 제네릭 타입은 타입 인자까지 그대로 쓴다: `{Array<Double>}`, `{List<List<Int>>}`(결정됨 2026-11-18; Java는 소거 후 비교, Kotlin `Array<T>`는 JVM에서 구분됨). 시그니처의 타입 자리에는 오버로딩을 유일하게 구분하는 데 필요한 표기를 모두 허용한다(결정됨 2026-11-18): 지금은 이름, 타입 인자, 배열 `[]`, nullable `?`(`foo{Double?}`; Kotlin `Double`/`Double?`는 JVM에서 `double`/`Double`이라 오버로딩됨). 와일드카드·변성·`*` 투영은 JVM에서 구분하지 못하므로 불필요. Kotlin 함수 타입은 `Function1<…>`로 쓰고 `(A) -> B` 표기는 아직 받지 않는다.
- **함수 정의 (결정됨 2026-11-18)**: 함수 리터럴 `(a: Real, b: Real) => 식`, 구조 분해 매개변수 `((x, y): Vec(2, Real)) => …`, 블록 `(n: Card) => { … }`(return으로 끝남, 그 전의 return은 `if … then` 아래에서만), 추론 가능하면 대수 생략 `i => x[i]`. 매개변수에는 대수만(해상도 없음, 호출마다 구체화). 반환 대수 = return들의 ∨, 재귀는 비재귀 return으로 구한 대수로 가정해 재검사. `fibo`의 반환 대수는 `Int`(`n-2`가 산술이라 Int). Card 매개변수 자리도 인덱스 자리처럼 Int → Card(음수는 ⊥). ⊤ → Int 변환은 ⊥처럼 오류(NPE, 컴파일 타임이면 컴파일 오류)라서 `fibo(inf)`는 오류. **`Not` 대수(결정됨 2026-11-18)**: Kotlin `Nothing`에 해당하는 원소 없는 대수를 문서에서 `Not`으로 쓴다. 모든 대수의 하위 대수(`Not ∨ K = K`), Not이 피연산자인 연산도 Not. 내장 함수 `error()`(인자 없음, 대수 Not, 실행 시 `com.doubledeltas.vel.VelException`을 던짐; `vel-runtime`, `RuntimeException` 상속, 결정됨 2026-11-18. `Error` 계열과의 혼동을 피해 `~Exception` 이름). Card의 ⊥(값)와 구분. 코드: `ScalarAlgebra.NOT`. 메시지 문자열은 미정(VEL에 문자열 없음). `Func(A × B, R)`와 `Func(Vec(2, A), R)`는 구분. 값으로 캡처. 문장형 `if c then 문장`(else 없음) 추가. 비교 `== != < <= > >=`(Bool, 원소별, 범위보다 낮고 조건식보다 높음; Complex는 순서 비교 불가). `=>`가 가장 낮은 우선순위. 렉서는 괄호 스택으로 블록 `{}` 안에서 줄바꿈을 다시 문장 구분자로 쓴다. (`type-system.md` 1.10, 3.5, `vel-grammar.md` 3.2, 4.10, 4.11)
- **`inf` (결정됨 2026-11-18)**: Card의 ⊤(`Card.TOP`)를 VEL에서 미리 선언된 이름 `inf`로 쓴다. ⊤는 무한대 ℵ0이며, 차원 크기로는 "⊤가 아닌 어떤 인덱스든 들어올 수 있고 실행 시 정해짐" → 길이 미정으로 해석. 대수 인자로는 무한 차원 배열(다항식 기저 등)도 나타낼 수 있다. `Vec(inf, Real, i => x[i])` 같은 생성 문법은 이후.
- 함수 대수는 아직 모델에 없다(`VelAlgebra`는 `ArrayAlgebra`만 허용). 함수 문법과 함께 추가한다. 표기는 `Func(A, B)`(결정됨 2026-11-18, `A ↦ B` 대신). `Func(A, B)`는 대수이고 A, B도 대수다. 구성자(`Array`, `Func`, `×`)의 인자 자리에 대수 대신 **해상도**나 **타입**을 넣을 수 있다(결정됨 2026-11-18; 입력/출력 해상도 분리안은 취소). 인자가 모두 해상도면 구조를 가진 해상도(`Func(F64, Z64)`, `F64 × Z32`), 모두 타입이면 타입. `⟨Func(A, B)⟩_{Func(R_A, R_B)} = Func(⟨A⟩_{R_A}, ⟨B⟩_{R_B})`, 단일 `R`은 모든 자리에 적용. 예: `DoubleToLongFunction` = `⟨Func(Real, Int)⟩_{Func(F64, Z64)}`, `Math.scalb` = `⟨Func(Real × Int, Real)⟩_{Func(F64 × Z32, F64)}`. 배열의 인덱스 집합에는 해상도가 없다. **배열의 해상도는 하나**이고, 해상도가 다른 원소로 만들면(성분 객체 등) 기본으로 가장 큰 해상도(언어별 ⊔, `java.md` 7.3)를 고른다(결정됨 2026-11-18). 해상도 연산 규칙은 구체화 규칙(`java.md` 7)으로 옮겨 정리했다.

## 6. 미정 / 추후 과제

- [ ] Kotlin 컴파일러 플러그인의 한계
  - IR 단계라서 오류가 IDE 편집기에는 표시되지 않고 빌드할 때만 나온다. IDE에 표시하려면 FIR 체커(`FirAdditionalCheckersExtension`)가 필요하다.
  - 컴파일러 플러그인 API는 Kotlin 버전마다 바뀌므로, Kotlin을 올릴 때 `vel-kotlinc`를 함께 확인해야 한다.
  - Gradle 사용자를 위한 Gradle 플러그인(`KotlinCompilerPluginSupportPlugin`)은 아직 없다. 지금은 `kotlinCompilerPluginClasspathMain`에 직접 추가한다.
- [ ] Kotlin 문자열 템플릿: `@Vel("...")` 안의 `$`는 Kotlin 템플릿으로 해석된다. VEL 식별자에 `$`가 있으면 `${'$'}`로 써야 한다.
- [ ] Kotlin 고유 기능의 매핑: `operator fun`을 정의한 사용자 타입, 기본값·이름 있는 인자, `IntRange` 등

- [ ] `@VelConfig` 설계: 최종 정밀도 `MathContext`, 작업 정밀도 등. 형식과 적용 범위(클래스, 메서드, 패키지)
- [ ] **함수 대수 구현**: 설계는 5절(함수 정의, 오버로딩 해소, 원소별 적용, `inf`)에서 결정됨. 정적 메서드 호출은 구현됨(위 "정적 메서드 호출 구현 메모"). 모델(`Func`), 타입 규칙, 함수 리터럴, VEL 함수 호출, 메서드 참조 값 `Math.abs{double}`, 인스턴스 메서드, `f.(x)`, `Vec(inf, Real, i => x[i])` 생성기가 남음.
- [ ] **함수 문법의 남은 설계**: 가변 인자, 제네릭, `void` 처리, `complex(a, b)` 같은 내장 함수, `error()` 메시지
- [ ] 구체화기 미지원: Complex/`j`, Z∞·Dn·D∞(`BigInteger`/`BigDecimal`), 행렬 나눗셈·거듭제곱, 분할, Bool 행렬 곱, 멤버 접근, 제네릭 메서드·소유 타입
- [ ] Kotlin 인라인(IR): 스칼라가 아닌 원소의 `Array` 결과(`Array<Point>`), 속성 참조
- [ ] 그 밖의 제어문(반복 등)과 성분별 조건 선택(Bool 배열 조건)
- [ ] 명시적 변환(cast)과 타입 표기 문법: `⊑`를 거스르는 대입에 필요
- [ ] 문법 구현 정리
  - `NUMBER`를 `INT`/`DECIMAL`로 분리(리터럴 해상도 규칙용)
  - 오류 메시지 개선: 인라인에 문장을 쓴 경우, 닫히지 않은 괄호
- [ ] 의미 분석에서 거부할 것: 인덱스 밖의 `:`·`end`. (`@Dim` 중복은 `DimSpec`, 첨자 밖의 분할점 체인과 `return` 규칙은 `StructureChecker`, 행 길이 불일치는 연결 규칙이 처리함)
- [ ] `java.md` 각주: `Character.codePointAt(char[], 0)` 표현이 부정확하다. `char` 하나의 값은 그냥 `(int) c`다.
- [ ] 구현 순서 제안
  1. ~~`vel-core` 타입 모델(크기 `⊤`, 브로드캐스팅, 연결 포함)~~ 완료
  2. ~~Java 매핑(원시 타입과 그 배열, `@Dim`)~~ 완료: `ModelBoundaries`. 래퍼·`List`·성분 객체 포함. `BigInteger`/`BigDecimal`만 남음.
  3. ~~타입 검사~~ 생성기 안에서 `TypeRules`로 수행(1차 완료)
  4. ~~B 방식 코드 생성(`<Owner>Vel` 구현 클래스)~~ 1차 완료(2026-11-18)
  5. ~~C 방식 치환(javac 플러그인, 내부 API 사용)~~ 1차 완료(2026-11-18)

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
