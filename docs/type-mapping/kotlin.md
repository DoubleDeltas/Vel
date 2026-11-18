## Kotlin과 VEL의 타입 매핑
Kotlin 타입 시스템 및 표준 라이브러리와의 기본 매핑을 정의한다.

정의되지 않은 타입은 직접 정의해야 한다.

대상은 **Kotlin/JVM**이다. Kotlin/JVM은 Java와 같은 JVM 위에서 동작하므로, 이 문서에 따로 적지 않은 부분은 [Java 매핑](./java.md)을 따른다. Kotlin/Native, Kotlin/JS, Kotlin/Wasm은 다루지 않는다.

Kotlin 소스의 VEL을 처리하는 방식(KSP, Kotlin 컴파일러 플러그인 등)은 이 문서에서 정하지 않는다.

## 1. 스칼라
### 1.1. 기본 타입
| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `Byte` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}8}$ |
| `Short` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}16}$ |
| `Int` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$ |
| `Long` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}64}$ |
| `Float` | $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$ |
| `Double` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ |
| `Boolean` | $\langle\mathrm{Bool}\rangle$ |
| `Char` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}16}$[^1] |

### 1.2. 부호 없는 정수
Java와 달리 Kotlin에는 부호 없는 정수 타입이 있으며, $Z^+$ 해상도에 그대로 대응한다.

| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `UByte` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}8}$ |
| `UShort` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}16}$ |
| `UInt` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}32}$ |
| `ULong` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}64}$ |

### 1.3. Nullable 타입
`Int?`, `Double?` 등 nullable 타입은 1.1절, 1.2절의 타입과 같은 VEL 타입으로 취급한다. 값이 `null`이면 `NullPointerException`을 던진다(Java의 래퍼 타입과 같음).

### 1.4. 임의 정밀도와 십진 부동소수
Kotlin/JVM은 `java.math`를 그대로 쓴다.

| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `java.math.BigInteger` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}\infty}$ |
| `java.math.BigDecimal` | $\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$ |

$\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$의 정밀도 처리와 $\langle\mathrm{Real}\rangle_{\mathrm{D}n}$의 표현은 Java 매핑 1.3절, 1.4절과 같다. 기본 최종 정밀도는 `MathContext.DECIMAL128`이다.

## 2. 시퀀스
이 문단에서 "시퀀스"는 다음 중 하나를 말하며, `a[]`로 표기한다.
- 원시 타입 배열: `ByteArray`, `ShortArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `BooleanArray`, `CharArray`
- 부호 없는 배열: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- 객체 배열: `Array<T>`
- `kotlin.collections.List<T>` (`MutableList<T>` 포함)

`Collection`, `Iterable`, `Sequence` 등 인덱스 접근이 보장되지 않는 타입은 시퀀스로 취급하지 않는다.

차원의 순서는 Java와 같다. 원소가 스칼라 타입 $\langle \mathbb K \rangle_R$인 시퀀스에 대해
- 길이 $n$인 1차원 시퀀스(예: `DoubleArray`)는 $\langle\mathrm{Vec}(n, \mathbb K)\rangle_R$로 취급한다.
- 2차원 시퀀스(예: 길이 $m$인 `Array<DoubleArray>`, 각 원소의 길이 $n$)는 $\langle\mathrm{Mat}(n, m, \mathbb K)\rangle_R$로 취급한다.

3차원 이상 시퀀스에 대해서도, 각 차원의 크기를 역순으로 놓은 배열로 취급한다.

### 2.1. 크기
각 차원의 크기는 기본적으로 $\top$이며, `@Dim`으로 지정한다(Java 매핑 2.1절). `value`와 `vel`은 VEL 순서(행 먼저), `java`는 JVM 선언 순서(바깥 배열부터)이며 셋 중 정확히 하나를 쓴다.

Kotlin에서는 `value` 인자를 가변 인자처럼 `@Dim(3, 4)`로, 이름 있는 배열 인자를 `[...]`로 쓴다.

| Kotlin 선언 | VEL 타입 |
| --- | --- |
| `v: DoubleArray` | $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(3) v: DoubleArray` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(3, 4) m: Array<DoubleArray>` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(java = [3]) v: DoubleArray` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(java = [4, 3]) m: Array<DoubleArray>` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(vel = [3, 4]) m: Array<DoubleArray>` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ (위와 같음) |
| `@Dim(java = [Dim.TOP, 3]) m: Array<DoubleArray>` | $\langle\mathrm{Mat}(3, \top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |

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

## 3. Kotlin 구조분해
연산자 `component1()`, …, `componentN()`을 참조할 수 있는 객체(예: `Pair`, `Triple`, data class)는 **성분 객체**이며, Java 매핑 2.5절의 규칙으로 배열이 된다.

각 성분 타입 $C_i \mapsto \langle\mathrm{Array}(\mathbf d_i, \mathbb K_i)\rangle_{R_i}$의 모양 $\mathbf d_i$가 모두 같고 스칼라 대수에 상한 $\mathbb K = \mathbb K_1 \vee \cdots \vee \mathbb K_N$이 있으면

$$ T \mapsto \langle\mathrm{Array}(N, \mathbf d, \mathbb K)\rangle_{R_1 \sqcup \cdots \sqcup R_N} $$

이다. 성분이 스칼라면 $\langle\mathrm{Vec}(N, \mathbb K)\rangle_R$, 길이 $M$인 벡터면 $\langle\mathrm{Mat}(N, M, \mathbb K)\rangle_R$(성분 $i$가 $i$번째 행)다. 스칼라 대수가 다르면 상한을, 해상도가 다르면 넓은 쪽을 따른다.

| Kotlin 타입 | VEL 타입 |
| --- | --- |
| `Pair<Double, Double>` | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `Pair<Int, Double>` | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `Triple<Float, Float, Float>` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}32}$ |
| `Pair<DoubleArray, DoubleArray>` | $\langle\mathrm{Mat}(2, \top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `data class V(val x: Double, val y: Double, val z: Double)` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `Pair<Int, Boolean>` | 배열이 아님 (스칼라 대수의 상한이 없음) |

반대로 VEL 값이 성분 객체 타입으로 나갈 때는 성분별로 나눠 그 객체로 감싼다(Java 매핑 5절).

```kotlin
fun rotate90(x: Double, y: Double): Pair<Double, Double> = vel("(-y, x)")
// VEL 값 ⟨Vec(2, Real)⟩_F64를 Pair(-y, x)로 감싼다
```

## 4. 함수
다음은 모든 인자 타입과 반환 타입이 VEL로 변환 가능하다면 VEL의 함수 타입으로 취급한다.
- 함수 타입 `(A, B) -> R`과 람다
- `fun interface`의 유일한 추상 함수
- 최상위 함수, `object`·`companion object`의 함수

`T` 타입의 멤버 함수와 **확장 함수** `fun T.foo(bar: U): R`는 수신 객체를 첫 인자로 취급해 $\mathrm{Func}(T \times U, R)$로 변환한다.

호출 문법은 Kotlin을 따른다.
- 멤버 함수, 확장 함수: `t.foo(x)`. `foo(t, x)` 형태로는 부를 수 없다.
- 최상위 함수: `foo(x)`
- `object`, `companion object`의 함수: `T.foo(x)`

오버로딩 해소는 Java 매핑 3.1절과 같다. 시그니처 지정 `{…}`에는 **Kotlin 타입**을 쓴다. 예: `maxOf{Double, Double}`, `foo{DoubleArray}`. 확장 함수의 수신 객체는 쓰지 않는다. 제네릭 타입은 타입 인자까지 Kotlin과 같이 쓴다. 예: `foo{Array<Double>}`, `bar{List<List<Int>>}`. JVM에서 `Array<Double>`과 `Array<Int>`는 서로 다른 타입이므로 타입 인자로 오버로딩이 구분될 수 있다. nullable 타입은 `?`까지 쓴다. `foo(x: Double)`과 `foo(x: Double?)`는 JVM에서 `double`과 `java.lang.Double`이라 오버로딩이 되므로 `foo{Double}`과 `foo{Double?}`로 구분한다. 함수 타입 매개변수는 `{Function1<Double, Double>}`처럼 쓴다(`(Double) -> Double` 표기는 아직 받지 않는다).

멤버 접근 `t.x`는 Kotlin 속성(getter 호출)이다. Kotlin과 같이 Java의 getter `getX()`도 `t.x`로 쓸 수 있다.

반환 타입이 nullable이고 `null`을 돌려주면 `NullPointerException`을 던진다. `suspend` 함수는 지원하지 않는다.

VEL의 불변성은 VEL 값에만 해당한다. 호출한 Kotlin 함수의 부수 효과는 막지 않는다.

### 4.1. `@Vel` 함수와 인라인 `vel("...")`
- `@Vel` 함수(KSP)는 소유 타입마다 `<Owner>Vel` 클래스로 생성된다. 인터페이스는 구현하고, 추상 클래스는 인자 없는 생성자로 상속한다. `@Vel`이 아닌 추상 멤버가 남으면 생성 클래스도 `abstract`다.
- 인라인 `vel("...")`(컴파일러 플러그인)은 타입 분석이 끝난 IR에서 생성 IR로 바뀐다. VEL에서 쓸 수 있는 이름은 매개변수, 호출보다 앞에 선언된 지역 변수, 람다 매개변수 중 타입이 원시 타입이나 그 배열인 것이다(크기는 `@Dim`). 속성은 아직 쓸 수 없다.
- 인라인 결과는 Kotlin이 호출에 추론한 타입으로 구체화된다. 예: `val h: Float = vel("g * t^2 / 2")`는 $\mathrm{F}64$로 계산해 `Float`로 바꾼다.
- 지원하는 타입: `Boolean`, `Byte`, `Short`, `Char`, `Int`, `Long`, `Float`, `Double`, 부호 없는 `UByte`, `UShort`, `UInt`, `ULong`(1.2절), 이들의 배열(`DoubleArray`, `UIntArray` 등)과 그 `Array`(`Array<UIntArray>` 포함), `List`, 다른 값의 `Array`(`Array<Double>` 등), 성분 객체(`Pair`, `Triple`, data class, 3절), 그리고 이 모든 타입의 nullable 타입(1.3절). `UShortArray`는 아직 지원하지 않는다. 인라인 `vel`의 결과로 다른 값의 `Array`는 원소가 스칼라일 때만 만들 수 있다.
- nullable 타입 `T?`는 `T`와 같은 대수로 변환한다. 값을 읽을 때 `null`이면 `NullPointerException`을 던진다. `Array<DoubleArray?>`처럼 안쪽이 nullable이면, 그 원소에 접근할 때 같은 예외가 난다.
- 부호 없는 정수의 연산은 해상도대로 계산한다. $\mathrm{Z}^{+}32$끼리의 덧셈은 $2^{32}$을 법으로 순환하고, 나눗셈·나머지·비교·실수 변환은 부호 없는 값으로 한다. `UShort`($\mathrm{Z}^{+}16$)는 내부에서 `Char`와 같은 표현을 쓴다.

## 5. 이름
VEL은 Kotlin의 지역 변수, 매개변수, 속성을 이름 그대로 참조한다.

VEL의 예약어 중 `return`, `true`, `false`, `if`, `else`는 Kotlin의 하드 키워드이기도 하다. `then`은 Kotlin에서 일반 이름이므로, `then`이라는 Kotlin 변수는 VEL에서 `` `then` ``으로 쓴다. 그 밖의 백틱 없이 선언된 Kotlin 이름은 모두 VEL에서 그대로 쓸 수 있다.

미리 선언된 이름(`j`, `end`)은 Java 매핑 4절과 같이 같은 이름의 Kotlin 변수보다 우선하며, Kotlin 변수는 백틱으로 쓴다(`` `j` ``).

VEL의 백틱 안에는 Java 식별자만 올 수 있다. 그래서 Kotlin에서 백틱으로 선언한 이름 중 Java 식별자가 아닌 것(예: `` `my value` ``)은 VEL에서 참조할 수 없다.

## 6. 연산자 대응
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

`j`, `end`는 미리 선언된 변수이며 대입하면 새 값에 다시 묶인다(Java 매핑 4절). VEL 밖으로 나가는 값의 변환은 Java 매핑 5절과 같고, 중간 계산의 해상도는 Java 매핑 7절(구체화 규칙)을 따른다.

[^1]: `Char`는 Kotlin에서 수가 아니므로, `Char.code`의 값(UTF-16 코드 유닛)을 취급한다.
