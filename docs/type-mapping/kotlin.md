## Kotlin과 VEL의 타입 매핑
Kotlin 타입 시스템 및 표준 라이브러리와의 기본 매핑을 정의한다.

정의되지 않은 타입은 직접 정의해야 한다.

대상은 **Kotlin/JVM**이다. Kotlin/JVM은 Java와 같은 JVM 위에서 동작하므로, 이 문서에 따로 적지 않은 부분은 [Java 매핑](./java.md)을 따른다. Kotlin/Native, Kotlin/JS, Kotlin/Wasm은 다루지 않는다.

Kotlin 소스의 VEL을 처리하는 방식(KSP, Kotlin 컴파일러 플러그인 등)은 이 문서에서 정하지 않는다.

## 1. 스칼라
### 1.1. 기본 타입
| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `Byte` | $\langle\mathrm{Real}\rangle_Z(8)$ |
| `Short` | $\langle\mathrm{Real}\rangle_Z(16)$ |
| `Int` | $\langle\mathrm{Real}\rangle_Z(32)$ |
| `Long` | $\langle\mathrm{Real}\rangle_Z(64)$ |
| `Float` | $\langle\mathrm{Real}\rangle_F(32)$ |
| `Double` | $\langle\mathrm{Real}\rangle_F(64)$ |
| `Boolean` | $\langle\mathrm{Bool}\rangle$ |
| `Char` | $\langle\mathrm{Real}\rangle_{Z+}(16)$[^1] |

### 1.2. 부호 없는 정수
Java와 달리 Kotlin에는 부호 없는 정수 타입이 있으며, $Z^+$ 해상도에 그대로 대응한다.

| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `UByte` | $\langle\mathrm{Real}\rangle_{Z+}(8)$ |
| `UShort` | $\langle\mathrm{Real}\rangle_{Z+}(16)$ |
| `UInt` | $\langle\mathrm{Real}\rangle_{Z+}(32)$ |
| `ULong` | $\langle\mathrm{Real}\rangle_{Z+}(64)$ |

### 1.3. Nullable 타입
`Int?`, `Double?` 등 nullable 타입은 1.1절, 1.2절의 타입과 같은 VEL 타입으로 취급한다. 값이 `null`이면 `NullPointerException`을 던진다(Java의 래퍼 타입과 같음).

### 1.4. 임의 정밀도와 십진 부동소수
Kotlin/JVM은 `java.math`를 그대로 쓴다.

| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `java.math.BigInteger` | $\langle\mathrm{Real}\rangle_Z(\infty)$ |
| `java.math.BigDecimal` | $\langle\mathrm{Real}\rangle_D(\infty)$ |

$\langle\mathrm{Real}\rangle_D(\infty)$의 정밀도 처리와 $\langle\mathrm{Real}\rangle_D(n)$의 표현은 Java 매핑 1.3절, 1.4절과 같다. 기본 최종 정밀도는 `MathContext.DECIMAL128`이다.

## 2. 시퀀스
이 문단에서 "시퀀스"는 다음 중 하나를 말하며, `a[]`로 표기한다.
- 원시 타입 배열: `ByteArray`, `ShortArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`, `CharArray`
- 부호 없는 배열: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- 객체 배열: `Array<T>`
- `kotlin.collections.List<T>` (`MutableList<T>` 포함)

`Collection`, `Iterable`, `Sequence` 등 인덱스 접근이 보장되지 않는 타입은 시퀀스로 취급하지 않는다.

차원의 순서는 Java와 같다. 스칼라 타입 $F$에 대해
- 1차원 시퀀스 $F[n]$(예: `DoubleArray`)는 $\mathrm{Vec}(n,F)$로 취급한다.
- 2차원 시퀀스 $F[m][n]$(예: 길이 $m$인 `Array<DoubleArray>`, 각 원소의 길이 $n$)는 $\mathrm{Mat}(n,m,F)$로 취급한다.

3차원 이상 시퀀스에 대해서도, 각 차원의 크기를 역순으로 놓은 배열로 취급한다.

### 2.1. 크기
각 차원의 크기는 기본적으로 $\top$이며, `@Dim`으로 지정한다(Java 매핑 2.1절). `value`와 `vel`은 VEL 순서(행 먼저), `java`는 JVM 선언 순서(바깥 배열부터)이며 셋 중 정확히 하나를 쓴다.

Kotlin에서는 `value` 인자를 가변 인자처럼 `@Dim(3, 4)`로, 이름 있는 배열 인자를 `[...]`로 쓴다.

| Kotlin 선언 | VEL 타입 |
| --- | --- |
| `v: DoubleArray` | $\mathrm{Vec}(\top, F)$ |
| `@Dim(3) v: DoubleArray` | $\mathrm{Vec}(3, F)$ |
| `@Dim(3, 4) m: Array<DoubleArray>` | $\mathrm{Mat}(3, 4, F)$ |
| `@Dim(java = [3]) v: DoubleArray` | $\mathrm{Vec}(3, F)$ |
| `@Dim(java = [4, 3]) m: Array<DoubleArray>` | $\mathrm{Mat}(3, 4, F)$ |
| `@Dim(vel = [3, 4]) m: Array<DoubleArray>` | $\mathrm{Mat}(3, 4, F)$ (위와 같음) |
| `@Dim(java = [Dim.TOP, 3]) m: Array<DoubleArray>` | $\mathrm{Mat}(3, \top, F)$ |

`@Dim`은 Java 어노테이션이므로 Kotlin 속성에 붙일 때는 적용 대상을 밝히는 것을 권장한다.
- 생성자 매개변수: `@param:Dim(...)`
- 뒷받침 필드: `@field:Dim(...)`
- getter(반환값): `@get:Dim(...)`

### 2.2. 범위 밖 접근, null, 들쭉날쭉한 배열
Java 매핑 2.2절, 2.3절과 같다.
- 크기가 $\top$인 차원에서 범위 밖 원소는 null로 취급하고, 연산에 쓰이면 `NullPointerException`을 던진다.
- `Array<Double?>`처럼 원소가 nullable이고 값이 `null`이어도 `NullPointerException`을 던진다.

### 2.3. 불변성
VEL 값은 불변이다(VEL 타입 시스템 1.5절). Kotlin에서 넘겨받은 배열과 `MutableList`는 VEL이 수정하지 않는다(Java 매핑 2.4절).

## 3. 함수
다음은 모든 인자 타입과 반환 타입이 VEL로 변환 가능하다면 VEL의 함수 타입으로 취급한다.
- 함수 타입 `(A, B) -> R`과 람다
- `fun interface`의 유일한 추상 함수
- 최상위 함수, `object`·`companion object`의 함수

`T` 타입의 멤버 함수와 **확장 함수** `fun T.foo(bar: U): R`는 수신 객체를 첫 인자로 취급해 $T \times U \mapsto R$로 변환한다.

호출 문법은 Kotlin을 따른다.
- 멤버 함수, 확장 함수: `t.foo(x)`. `foo(t, x)` 형태로는 부를 수 없다.
- 최상위 함수: `foo(x)`
- `object`, `companion object`의 함수: `T.foo(x)`

멤버 접근 `t.x`는 Kotlin 속성(getter 호출)이다. Kotlin과 같이 Java의 getter `getX()`도 `t.x`로 쓸 수 있다.

반환 타입이 nullable이고 `null`을 돌려주면 `NullPointerException`을 던진다. `suspend` 함수는 지원하지 않는다.

VEL의 불변성은 VEL 값에만 해당한다. 호출한 Kotlin 함수의 부수 효과는 막지 않는다.

## 4. 이름
VEL은 Kotlin의 지역 변수, 매개변수, 속성을 이름 그대로 참조한다.

VEL의 예약어 `return`, `true`, `false`는 Kotlin의 하드 키워드이기도 하다. 따라서 백틱 없이 선언된 Kotlin 이름은 모두 VEL에서 그대로 쓸 수 있다.

미리 선언된 이름(`j`, `end`)은 Java 매핑 4절과 같이 같은 이름의 Kotlin 변수보다 우선하며, Kotlin 변수는 백틱으로 쓴다(`` `j` ``).

VEL의 백틱 안에는 Java 식별자만 올 수 있다. 그래서 Kotlin에서 백틱으로 선언한 이름 중 Java 식별자가 아닌 것(예: `` `my value` ``)은 VEL에서 참조할 수 없다.

## 5. 연산자 대응
Kotlin 코드를 VEL로 옮길 때 나눗셈 계열은 다음과 같이 대응한다.

| Kotlin | VEL | 반올림 방향 |
| --- | --- | --- |
| `a / b` (실수) | `a / b` | 없음 |
| `a / b` (정수) | `a /// b` | 0 방향 |
| `a % b`, `a.rem(b)` | `a %%% b` | 0 방향 |
| `a.floorDiv(b)` | `a // b` | $-\infty$ 방향 |
| `a.mod(b)` | `a %% b` | $-\infty$ 방향 |

VEL의 `/`는 정수끼리도 참 나눗셈이므로, Kotlin의 정수 `a / b`를 그대로 옮기면 결과가 달라진다.

Boolean 연산은 다음과 같이 대응한다.

| Kotlin | VEL |
| --- | --- |
| `a or b` | `a + b` |
| `a and b` | `a * b` |
| `a xor b` | `a o+ b` |

`j`, `end`는 미리 선언된 변수이며 대입하면 새 값에 다시 묶인다(Java 매핑 4절). VEL 밖으로 나가는 값의 변환은 Java 매핑 5절과 같다.

[^1]: `Char`는 Kotlin에서 수가 아니므로, `Char.code`의 값(UTF-16 코드 유닛)을 취급한다.
