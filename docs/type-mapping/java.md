## Java와 VEL의 타입 매핑
Java 타입 시스템 및 SDK에서 정의된 라이브러리와의 기본 매핑을 정의한다.

정의되지 않은 타입은 직접 정의해야 한다.

## 1. 스칼라
### 1.1. 원시 타입 (Primitive Types)
| Java 타입 | VEL 타입 |
| --- | --- |
| `byte` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}8}$ |
| `short` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}16}$ |
| `int` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$ |
| `long` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}64}$ |
| `float` | $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$ |
| `double` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ |
| `boolean` | $\langle\mathrm{Bool}\rangle$ |
| `char` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}16}$[^1] |

### 1.2. Wrapper Types
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.lang.Byte` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}8}$ |
| `java.lang.Short` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}16}$ |
| `java.lang.Integer` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$ |
| `java.lang.Long` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}64}$ |
| `java.lang.Float` | $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$ |
| `java.lang.Double` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ |
| `java.lang.Boolean` | $\langle\mathrm{Bool}\rangle$ |
| `java.lang.Character` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}^{+}16}$[^1] |

래퍼 타입의 값이 `null`이면 `NullPointerException`을 던진다.

### 1.3. 임의 정밀도
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.math.BigInteger` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}\infty}$ |
| `java.math.BigDecimal` | $\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$ |

$\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$의 정밀도 처리(7.5절)는 다음과 같이 구체화한다.

| 항목 | 기본값 |
| --- | --- |
| 최종 정밀도 문맥 | `java.math.MathContext.DECIMAL128` (유효숫자 34자리, `HALF_EVEN`) |
| 작업 정밀도 | 최종 정밀도의 2배 자릿수, 같은 반올림 모드 (DECIMAL128 기준 68자리, `HALF_EVEN`) |

- 두 값 모두 이후 `@VelConfig`로 바꿀 수 있게 한다.
- 중간 연산의 $+$, $-$, $\times$는 `MathContext` 없이(`BigDecimal.add/subtract/multiply`) 정확하게 계산한다.
- 나눗셈은 분자와 분모를 따로 유지한다.
- 최종 결과는 다음 순서로 만든다.
  1. 먼저 `p.divide(q)`로 정확한 나눗셈을 시도한다.
  2. 나누어떨어지지 않아 `ArithmeticException`이 나면 `p.divide(q, mathContext)`로 반올림한다.
  - 정확히 나누어떨어지는지는 분모의 소인수가 2와 5뿐인지로 미리 판정할 수도 있다.

### 1.4. 십진 부동소수
JDK에는 $\langle\mathrm{Real}\rangle_{\mathrm{D}n}$에 대응하는 타입이 없다. 이 해상도의 값이 Java로 나갈 때는 `BigDecimal`로 표현하고, 너비에 맞는 문맥(`MathContext.DECIMAL32`, `DECIMAL64`, `DECIMAL128`)으로 반올림한다.

## 2. 시퀀스
이 문단에서 "시퀀스"는 다음 중 하나를 말하며, `a[]`로 표기한다.
- Java 배열(`int[]`)
- `java.util.List`(`List<Integer>`)

원소가 스칼라 타입 $\langle \mathbb K \rangle_R$(스칼라 대수 $\mathbb K$, 해상도 $R$)인 시퀀스에 대해
- 길이 $n$인 1차원 시퀀스 `a[n]`은 $\langle\mathrm{Vec}(n, \mathbb K)\rangle_R$로 취급한다.
- 2차원 시퀀스 `a[m][n]`은 $\langle\mathrm{Mat}(n, m, \mathbb K)\rangle_R$로 취급한다.

3차원 이상 시퀀스에 대해서도, 각 차원의 크기를 역순으로 놓은 배열로 취급한다.

### 2.1. 크기
Java 배열과 `List`의 길이는 컴파일 타임에 알 수 없으므로, 각 차원의 크기는 기본적으로 $\top$이다(VEL 타입 시스템 1.2절).

`@Dim` 어노테이션으로 크기를 지정할 수 있다. 다음 중 **정확히 하나**를 쓴다. 둘 이상 쓰거나 모두 생략하면 컴파일 오류(`VelTypeException`)다.
- `value`(기본 요소, `@Dim(...)`): `vel`과 같다.
- `vel`: **VEL 순서**(행 먼저. $\mathrm{Mat}(m, n)$이면 `{m, n}`)
- `java`: **Java 선언 순서**(바깥 배열부터)

2절의 매핑 때문에 VEL 순서와 Java 순서는 서로 뒤집혀 있다. 모르는 차원은 `Dim.TOP`으로 둔다.

| Java 선언 | VEL 타입 |
| --- | --- |
| `double[] v` | $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(3) double[] v` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim({3, 4}) double[][] m` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(java = 3) double[] v` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(java = {4, 3}) double[][] m` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `@Dim(vel = {3, 4}) double[][] m` | $\langle\mathrm{Mat}(3, 4, \mathrm{Real})\rangle_{\mathrm{F}64}$ (위와 같음) |
| `@Dim(java = {Dim.TOP, 3}) double[][] m` | $\langle\mathrm{Mat}(3, \top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |

`@Dim`은 매개변수, 필드, 지역 변수, 메서드(반환값)에 붙일 수 있다.
- 지정한 크기는 신뢰하며 런타임에 검사하지 않는다.
- 실제 길이가 지정한 크기와 다를 때의 동작은 정의하지 않는다.

### 2.2. 범위 밖 접근과 null
크기가 $\top$인 차원에서 실제 길이 이상의 인덱스에 있는 원소는 null로 취급하고, 연산에 쓰이면 `NullPointerException`을 던진다.
- Java 배열의 범위 밖 접근에서 생기는 `ArrayIndexOutOfBoundsException`, `List`의 `IndexOutOfBoundsException`은 `NullPointerException`으로 보고한다.
- 시퀀스 자체나 원소(래퍼 타입)가 `null`이어도 `NullPointerException`을 던진다.

### 2.3. 들쭉날쭉한 배열 (jagged array)
`double[][]`처럼 안쪽 배열의 길이가 서로 다를 수 있는 경우에도 별도 처리를 하지 않는다.
- 안쪽 차원의 크기는 $\top$으로 보고 2.2절을 따른다.
- 짧은 행의 범위 밖 원소는 null이 되어, 연산에 쓰이면 `NullPointerException`이 발생한다.

### 2.4. 불변성과 Java 배열
VEL 값은 불변이다(VEL 타입 시스템 1.5절). 따라서 Java에서 넘겨받은 배열과 `List`는 **VEL이 수정하지 않는다.**
- `@Vel` 메서드 안에서 매개변수 `m`에 `m[i] = x`를 해도 호출한 쪽의 배열은 바뀌지 않는다. VEL 안에서만 `m`이 복사된 새 값을 가리킨다.
- 복사 생략 최적화는 VEL이 직접 만든 값에만 적용한다. Java에서 넘겨받은 배열은 Java 쪽에서 계속 참조할 수 있으므로, 고치기 전에 반드시 복사한다.
- VEL 밖으로 나가는 배열 값(`return` 등)은 새로 만든 Java 배열이다.

### 2.5. 성분 객체
다음 타입 $T$를 **성분 객체**라 하며, 성분 타입 $C_1, \cdots, C_N$을 갖는다.
- `record`: 레코드 구성 요소(선언 순서)
- 메서드 `component1()`, …, `componentN()`이 있는 클래스(예: Kotlin의 `Pair`, `Triple`, data class): 각 메서드의 반환 타입

각 성분 타입이 VEL 타입 $C_i \mapsto \langle\mathrm{Array}(\mathbf d_i, \mathbb K_i)\rangle_{R_i}$로 변환되고, 다음을 만족하면 $T$는 배열 타입이다.
- 모든 성분 대수의 모양이 같다: $\mathbf d_1 = \cdots = \mathbf d_N = \mathbf d$ ($\top$은 VEL 타입 시스템 1.2절을 따른다)
- 모든 성분의 스칼라 대수에 상한이 있다: $\mathbb K = \mathbb K_1 \vee \cdots \vee \mathbb K_N$ (VEL 타입 시스템 3.1절)

$$ T \mapsto \langle\mathrm{Array}(N, \mathbf d, \mathbb K)\rangle_{R_1 \sqcup \cdots \sqcup R_N} $$

즉 성분의 모양 앞에 크기 $N$인 차원을 붙인다. 성분 $i$는 첫 인덱스가 $i$인 부분 $T[i, \cdots]$다. 스칼라 대수가 서로 다르면 상한($\vee$)을, 해상도가 서로 다르면 넓은 쪽($\sqcup$, 7.3절)을 따른다. 예를 들어 $\mathrm{Int}$와 $\mathrm{Real}$ 성분이 섞이면 $\mathrm{Real}$이 되고, 해상도도 실수 해상도가 된다. 위 조건을 만족하지 않는 객체는 배열이 아니다.
- 성분이 스칼라면 $\mathrm{Array}(N) = \mathrm{Vec}(N)$이다.
- 성분이 길이 $M$인 벡터면 $\mathrm{Array}(N, M, 1) = \mathrm{Mat}(N, M)$이다. 성분 $i$는 $i$번째 행이다.
- 성분이 행렬이면 3차원 배열이 된다.

| $T$ | 성분 | VEL 타입 |
| --- | --- | --- |
| `record P(double x, double y)` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$, $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record Q(int a, double b)` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$, $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record R(@Dim(3) double[] u, @Dim(3) double[] v)` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$, $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Mat}(2, 3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record S(double[] u, double[] v)` | $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$, $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Mat}(2, \top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record B(int a, boolean b)` | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$, $\langle\mathrm{Bool}\rangle$ | 배열이 아님 (스칼라 대수의 상한이 없음) |

## 3. 메소드와 함수형 인터페이스 (Functional Interfaces)
정적 메소드와 함수형 인터페이스의 유일한 함수의 모든 인자 타입과 반환 타입이 VEL로 변환 가능하다면 VEL의 함수 타입으로 취급한다.

`T` 타입의 동적 메소드 `R foo(U bar)`는 가장 첫 인자를 `T`로 취급해 $\mathrm{Func}(T \times U, R)$로 변환한다.

호출 문법은 Java를 따른다.
- 동적 메소드: `t.foo(x)`. `foo(t, x)` 형태로는 부를 수 없다.
- 정적 메소드: `T.foo(x)` (정규화된 이름 `java.lang.Math.sqrt(x)`도 가능)

### 3.1. 오버로딩 해소
Java 메서드의 시그니처는 **이름과 매개변수 타입 목록**이다(JLS 8.4.2). 반환 타입은 포함되지 않으며, 제네릭 매개변수는 소거(erasure)한 타입으로 비교한다.

**시그니처 지정** `{…}`: 메서드 이름 뒤에 매개변수 타입을 **Java 타입**으로 써서 오버로딩 중 하나를 고른다.
- 형태: `Math.abs{double}`, `Math.max{int, int}`, `v.norm{}`(매개변수 없음), `foo{double[]}`, `foo{java.math.BigDecimal}`
- 동적 메서드는 선언된 매개변수만 쓴다(수신 객체는 쓰지 않는다).
- 호출과 원소별 적용에도 쓸 수 있다: `Math.abs{double}(x)`, `Math.abs{double}.(v)`
- 호출 없이 쓰면 **함수 값**이다. 이 값은 메서드의 타입을 추상화한 함수 대수를 갖는다(3절).

```
abs = Math.abs{double}   # ⟨Func(Real, Real)⟩_F64
return abs(-2)           # -2는 매개변수 해상도 F64로 구체화되어 2.0
```

**시그니처 없이 호출** `Math.abs(x)`: Java의 오버로딩 해소 규칙(JLS 15.12, 적용 가능한 후보 중 가장 구체적인 것)을 구체화할 때 적용한다. 인자의 해상도는 7.3절의 중간 계산 해상도를 쓴다.
- 예: `x`가 `float`이면 `Math.abs(float)`
- 예: `Math.abs(-2)`는 상수의 기본 해상도가 $\mathrm{Z}32$(7.6절)이므로 `Math.abs(int)`이고 결과는 $\mathrm{Int}$ `2`다. `Math.abs{double}(-2)`의 결과 `2.0`과 다르다.
- 가장 구체적인 후보가 하나로 정해지지 않으면 컴파일 오류다.

**시그니처 없이 참조** `f = Math.abs`: 그 이름의 메서드가 하나뿐이면 그 메서드를 가리킨다. 오버로딩이 여럿이면 모호하므로 컴파일 오류이며, `{…}`로 골라야 한다.

VEL의 불변성은 VEL 값에만 해당한다. 호출한 Java 메서드가 객체의 상태를 바꾸는 부수 효과는 막지 않는다.

## 4. 이름
VEL은 Java의 지역 변수, 매개변수, 필드를 이름 그대로 참조한다. VEL 식별자는 Java 식별자와 같은 문자 집합을 쓴다. VEL의 예약어는 `return`, `true`, `false`, `if`, `else`, `then`이며, `then`을 뺀 나머지는 Java 예약어이기도 하다. `then`이라는 Java 변수는 `` `then` ``으로 쓴다.

VEL이 미리 선언한 이름(`j`, `end`, 내장 함수 `abs` 등)은 같은 이름의 Java 변수보다 **우선한다**. VEL 안에서 대입하면 다른 변수처럼 가려진다(VEL 문법 2.5절). Java 변수를 가리키려면 Kotlin처럼 이름을 백틱으로 감싼다.

| VEL 표기 | 의미 |
| --- | --- |
| `j` | 허수 단위 $\mathrm{complex}(0, 1)$ |
| `` `j` `` | Java 변수 `j` |
| `end` (인덱스 안) | 그 차원의 길이 (마지막 인덱스는 `end-1`) |
| `` `end` `` | Java 변수 `end` |

백틱 안에는 Java 식별자만 쓸 수 있다. 미리 선언되지 않은 이름에 백틱을 붙여도 된다(`` `x` ``는 `x`와 같다).

`j`와 `end`는 VEL의 키워드가 아니라 **미리 선언된 변수**다. 대입하면 다른 변수처럼 새 값에 다시 묶인다(예: `j = 3` 이후의 `j`는 3).

## 5. VEL 밖으로 나가는 값
`return` 값이나 Java 변수에 저장되는 값은 대상 Java 타입으로 구체화된다(7절). 대상의 해상도가 더 낮으면 손실을 허용한다. 예: VEL에서 $\mathrm{D}\infty$로 계산한 결과를 `double`을 반환하는 `@Vel` 메서드에서 돌려주면 `double`로 반올림된다.

대상 타입이 **성분 객체**(2.5절)면, VEL 값을 성분별로 나눠 그 객체로 감싼다.
- VEL 값 $V$의 모양은 $T$의 VEL 타입과 맞아야 한다.
- 성분 $V[0, \cdots], \cdots, V[N-1, \cdots]$를 각 성분 타입으로 변환한 뒤, 성분 순서대로 받는 생성자로 $T$를 만든다. `record`는 표준 생성자, Kotlin `Pair`, `Triple`, data class는 주 생성자를 쓴다.
- 그런 생성자가 없는 타입으로는 내보낼 수 없다.

```java
record Point(double x, double y) {}

@Vel("(-p[1], p[0])")        // Point는 ⟨Vec(2, Real)⟩_F64이므로 성분을 인덱스로 읽는다
Point rotate90(Point p);     // 결과 ⟨Vec(2, Real)⟩_F64를 new Point(…, …)로 감싼다
```

## 6. Java 문자열 안의 VEL
`@Vel("...")`과 `vel("...")`의 VEL 코드는 Java 문자열 리터럴이므로 Java의 이스케이프 규칙이 먼저 적용된다. 백슬래시와 큰따옴표는 Java 문자열 규칙대로 써야 한다. 텍스트 블록(`"""..."""`)을 쓰면 여러 줄을 그대로 쓸 수 있다.

## 7. 구체화 규칙
VEL 식은 대수만 다룬다(VEL 타입 시스템 0.3절, 3장). 실제 계산의 해상도는 이 절의 규칙으로 정한다. 입력 값의 해상도(추상화하기 전의 Java 타입)와 구체화 지점의 해상도를 보고 각 중간 계산의 해상도를 정한 뒤, 그 해상도의 범위, 오버플로 규칙, 캐스팅 규칙, 유효 자릿수를 따르는 Java 코드를 생성한다. Kotlin도 이 규칙을 따른다.

### 7.1. 구체화 지점
값이 Java 타입으로 구체화되는 곳은 다음과 같다.
- `return`, Java 변수·필드에 대입: 대상 타입(5절)
- Java 메서드와 함수형 인터페이스의 인자: 매개변수 타입
- 인덱스, 범위 경계: `int`. $\langle\mathrm{Card}\rangle$도 `int`로 구체화하며, 산술에서는 $\mathrm{Z}32$로 본다.
- 조건식의 조건: `boolean`

VEL 지역 변수에 대입하는 것은 구체화 지점이 아니다.

$\mathrm{Not}$ 대수의 식(`error()`)은 구체화하면 `throw new com.doubledeltas.vel.VelException()`이 된다. `VelException`은 `vel-runtime`에 있으며 `RuntimeException`을 상속하므로 `throws` 선언이 필요 없다. ⊥나 ⊤ → Int 변환 오류는 이와 달리 `NullPointerException`이다. 값이 없으므로 어떤 Java 타입의 자리에도 쓸 수 있다.

### 7.2. 표현 순서
해상도 $A$의 모든 값을 해상도 $B$가 **정확히** 표현할 수 있을 때 $A \sqsubseteq B$로 쓴다.

| 관계 | 조건 |
| --- | --- |
| $\mathrm{Z}a \sqsubseteq \mathrm{Z}b$ | $a \le b$ |
| $\mathrm{Z}^{+}a \sqsubseteq \mathrm{Z}^{+}b$ | $a \le b$ |
| $\mathrm{Z}^{+}a \sqsubseteq \mathrm{Z}b$ | $a < b$ |
| $\mathrm{Z}a,\ \mathrm{Z}^{+}a \sqsubseteq \mathrm{Z}\infty$ | 항상 |
| $\mathrm{F}a \sqsubseteq \mathrm{F}b$ | $a \le b$ |
| $\mathrm{Z}a \sqsubseteq \mathrm{F}b$ | $a - 1 \le p(b)$ ($p(b)$: 가수 비트 수 + 1. $p(32)=24$, $p(64)=53$) |
| $\mathrm{Z}^{+}a \sqsubseteq \mathrm{F}b$ | $a \le p(b)$ |
| $\mathrm{D}a \sqsubseteq \mathrm{D}b$ | $a \le b$ |
| $\mathrm{Z}a \sqsubseteq \mathrm{D}b$ | $2^{a-1} \le 10^{q(b)}$ ($q(b)$: 십진 유효자릿수. $q(32)=7$, $q(64)=16$, $q(128)=34$) |
| $\mathrm{Z}^{+}a \sqsubseteq \mathrm{D}b$ | $2^{a} \le 10^{q(b)}$ |
| $\mathrm{Z}\infty,\ \mathrm{F}a,\ \mathrm{D}a \sqsubseteq \mathrm{D}\infty$ | 항상 ($\mathrm{F}a$, $\mathrm{D}a$의 NaN, $\pm\infty$ 제외) |

$\mathrm{Z}a \sqsubseteq \mathrm{Z}^{+}b$는 성립하지 않는다. 이진 부동소수와 십진 부동소수 사이($\mathrm{F}a$와 $\mathrm{D}b$)도 서로 정확히 표현하지 못하므로 $\sqsubseteq$가 성립하지 않는다.

$\sqsubseteq$는 **정확한 변환이 보장되는 방향**이다. 리터럴의 해상도(7.6절)와 오버로딩 해소에 쓴다.

중간 계산은 해상도를 최대한 높은 쪽으로 유지한다. 구체화 지점(7.1절)에서 대상의 해상도가 더 낮으면 그 해상도로 변환하며, 이때의 손실은 허용한다. 변환은 7.4절 "반올림"을 따른다. 이 정책은 이후 `@VelConfig`로 바꿀 수 있게 한다(예: 중간 계산에서도 낮은 해상도를 쓰는 fastmath).

### 7.3. 중간 계산의 해상도
피연산자 해상도 $A$, $B$에 대해 이항 연산의 해상도 $A \sqcup B$는 아래 표에서 **위에서부터 처음 맞는 행**으로 정한다.

| # | 조건 | 결과 |
| --- | --- | --- |
| 1 | 어느 한쪽이 $\mathrm{D}\infty$ | $\mathrm{D}\infty$ |
| 2 | 한쪽이 $\mathrm{Z}\infty$, 다른 쪽이 $\mathrm{F}n$ 또는 $\mathrm{D}n$ | $\mathrm{D}\infty$ |
| 3 | 어느 한쪽이 $\mathrm{Z}\infty$ | $\mathrm{Z}\infty$ |
| 4 | 어느 한쪽이 $\mathrm{D}n$ | $\mathrm{D}m$ — $m$은 양쪽 $\mathrm{D}$, $\mathrm{F}$의 너비 중 최댓값(정수 쪽은 무시) |
| 5 | 어느 한쪽이 $\mathrm{F}n$ | $\mathrm{F}m$ — $m$은 양쪽 $\mathrm{F}$의 너비 중 최댓값(정수 쪽은 무시) |
| 6 | $\mathrm{Z}a$, $\mathrm{Z}b$ | $\mathrm{Z}[\max(a, b)]$ |
| 7 | $\mathrm{Z}^{+}a$, $\mathrm{Z}^{+}b$ | $\mathrm{Z}^{+}[\max(a, b)]$ |
| 8 | $\mathrm{Z}a$, $\mathrm{Z}^{+}b$ | $\mathrm{Z}[\max(a, b + 1)]$ |

규칙 4, 5는 정확성보다 성능을 택한 규칙이다. **정수와 부동소수가 섞이면 정수의 너비와 관계없이 부동소수로 승격한다.** 부동소수가 둘이면 더 높은 너비를 따른다. 7.2절의 "중간 계산은 해상도를 최대한 높게 유지" 정책도 이 규칙을 바꾸지 않는다.
- 예: $\mathrm{Z}64 \sqcup \mathrm{F}32 = \mathrm{F}32$이며, 정밀도 손실이 있을 수 있다. 이는 Java의 이진 수치 승격과 같다.
- 십진 부동소수 $\mathrm{D}n$도 같은 방식으로 승격하며, 이진 부동소수와 섞이면 십진 쪽을 따른다. 예: $\mathrm{D}32 \sqcup \mathrm{F}64 = \mathrm{D}64$

연결, 튜플 쌓기, 조건식, 인덱스 대입, 성분 객체처럼 산술이 아닌 구조 연산도 원소 해상도를 $\sqcup$로 합친다. 단 아래의 최소 연산 폭은 적용하지 않는다.

#### 최소 연산 폭
정수 산술 결과(규칙 6–8)의 폭은 최소 32비트로 올린다. 즉 $\mathrm{Z}8 + \mathrm{Z}8 = \mathrm{Z}32$.
- 바이트·문자 데이터를 누적할 때 생기는 의도치 않은 오버플로를 줄인다.
- Java의 정수 승격 규칙과 일치하므로 생성 코드에 축소 변환(cast)이 필요 없다.

#### 연산자별 예외
| 연산 | 해상도 |
| --- | --- |
| 단항 $-$ on $\mathrm{Z}^{+}n$ | $\mathrm{Z}[n+1]$ (최소 연산 폭 적용) |
| $\mathrm{Z}^{+}a - \mathrm{Z}^{+}b$ | $\mathrm{Z}[\max(a, b) + 1]$ (최소 연산 폭 적용) |
| $\mathrm{Int}$ $/$ $\mathrm{Int}$ | $\mathrm{F}64$ ($\mathrm{Z}\infty$가 포함되면 $\mathrm{D}\infty$) |
| 몫과 나머지 (`//`, `///`, `%%`, `%%%`) | $\sqcup$ 규칙(정수끼리는 정수) |
| $A$ `^` 음이 아닌 정수 리터럴 | $A$ (최소 연산 폭 적용) |
| $A$ `^` $B$ (그 외) | $A \sqcup B \sqcup \mathrm{F}64$. 지수 $B$의 해상도도 반영한다. 예: $\mathrm{Z}32$ `^` $\mathrm{D}64$ $= \mathrm{D}64$ |

#### 행렬 연산
- **`*` (행렬 곱)**: 두 원소 해상도의 $\sqcup$를 따른다.
- **`/`, `/>`, `/<`**: $A \sqcup B$에 $\mathrm{Int}$ $/$ $\mathrm{Int}$ 규칙을 적용한다.
  - 정수 → $\mathrm{F}64$
  - $\mathrm{Z}\infty$ 포함 → $\mathrm{D}\infty$
  - $\mathrm{F}n$, $\mathrm{D}n$, $\mathrm{D}\infty$ → 그대로
  - $\mathrm{D}\infty$의 역행렬은 사칙연산만으로 구하므로 분수 표현으로 정확하게 계산한다.
- **`^` (행렬 거듭제곱)**: 음이 아닌 정수 리터럴 지수는 해상도를 유지한다. 그 외 지수 $B$는 $A \sqcup B \sqcup \mathrm{F}64$다.
- **`'`, `.'` (전치)**: 해상도를 바꾸지 않는다.

#### 대수와 해상도 맞추기
대수(VEL 타입 시스템 3장)와 해상도를 따로 정한 뒤, 조합 제한(VEL 타입 시스템 2장)을 지키도록 맞춘다.
- 대수가 $\mathrm{Real}$이나 $\mathrm{Complex}$인데 해상도가 정수 해상도면, 해상도를 $\mathrm{Z}n \to \mathrm{F}64$, $\mathrm{Z}\infty \to \mathrm{D}\infty$로 바꾼다. 예: `j * x`(`x`가 `int`)는 $\mathrm{F}64$로 계산한다.
- 해상도가 정수 해상도가 아닌데 대수가 $\mathrm{Int}$면, 대수를 $\mathrm{Real}$로 본다.

### 7.4. 오버플로와 반올림
고정 폭 정수 $\mathrm{Z}n$, $\mathrm{Z}^{+}n$의 연산 결과가 범위를 넘으면 2의 보수로 순환(wrap)한다.

부동소수 연산과 해상도 변환의 반올림, 오버플로는 IEEE 754의 기본 방식을 따른다.
- 반올림: 가장 가까운 값으로, 같은 거리면 짝수 쪽으로(roundTiesToEven)
- 부동소수 오버플로: $\pm\infty$

### 7.5. $\mathrm{D}\infty$의 정밀도
$\mathrm{D}\infty$는 **중간 계산에서 반올림 오차를 최대한 누적하지 않고**, **구체화 지점에서 필요할 때만 한 번 반올림**한다.

**중간 계산**
- $+$, $-$, $\times$, 정수 지수 거듭제곱: 유한 십진수끼리의 연산이므로 그대로 정확하다.
- $/$: 즉시 반올림하지 않는다. 값을 분수 $p/q$($p, q$는 $\mathrm{D}\infty$, $q > 0$)로 유지하고, 이후 연산도 분수 연산으로 이어간다.
  - 예: $a/b + c/d = (ad + cb)/(bd)$
  - 이렇게 하면 나눗셈은 최종 결과에서 한 번만 일어난다.
- 비교: 분수끼리 교차 곱으로 비교하므로 반올림 없이 정확하다.
- 정확히 표현할 수 없는 연산(정수가 아닌 지수, 이후 추가될 $\sqrt{\ }$·초월 함수 등): 중간 반올림이 불가피하다.
  - 이때는 최종 정밀도보다 높은 **작업 정밀도**로 계산한다.
  - 중간 반올림이 일어나는 경우는 이것뿐이다.

**구체화 지점**(7.1절)에서는 다음과 같이 처리한다.
- 분수 $p/q$가 유한 십진수로 나누어떨어지면 **반올림 없이 정확한 값**을 낸다.
- 그렇지 않을 때만(예: $1/3$) **최종 정밀도 문맥**으로 반올림한다.

최종 정밀도 문맥과 작업 정밀도의 기본값과 Java 구현은 1.3절에 둔다.

분수 표현은 연산이 이어질수록 분자·분모의 자릿수가 커질 수 있다. 반복 누적에서는 정확도를 얻는 대신 비용이 든다.

### 7.6. 리터럴
리터럴(VEL 타입 시스템 3.3절)은 함께 쓰인 피연산자의 해상도를 따른다.
- 정수 리터럴은 상대 해상도가 그 값을 정확히 표현할 수 있으면($\sqsubseteq$) 그 해상도를 따른다. 예: `2 * x`에서 `x`가 `float`이면 $\mathrm{F}32$, `x`가 `byte`이면 `2`도 $\mathrm{Z}8$이다(결과는 최소 연산 폭에 따라 $\mathrm{Z}32$).
- 소수 리터럴은 상대가 $\mathrm{F}n$, $\mathrm{D}n$, $\mathrm{D}\infty$일 때만 그 해상도를 따른다. 예: `0.5 * x`에서 `x`가 `float`이면 `0.5`도 $\mathrm{F}32$.
- 분수 리터럴은 소수 리터럴처럼 상대를 따르며, $\mathrm{D}\infty$에서는 분수 그대로 정확히 표현된다.
- 따를 상대가 없거나 표현할 수 없으면 기본 해상도를 쓴다.
  - 정수 리터럴: $\mathrm{Z}32$, 범위를 넘으면 $\mathrm{Z}64$, 그것도 넘으면 $\mathrm{Z}\infty$
  - 소수 리터럴: $\mathrm{F}64$

### 7.7. 배열
배열 간 원소별 연산의 원소 해상도는 위 규칙을 원소 해상도에 적용해 정한다.
$$ \langle\mathrm{Array}(\mathbf d, \mathbb K_1)\rangle_A \circ \langle\mathrm{Array}(\mathbf d, \mathbb K_2)\rangle_B \;\Rightarrow\; \text{원소 해상도 } A \sqcup B $$

[^1]: `Character.codePointAt(char[], 0)`를 이용해 코드포인트 값을 취급한다.