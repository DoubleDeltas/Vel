## Java와 VEL의 타입 매핑
Java 타입 시스템 및 SDK에서 정의된 라이브러리와의 기본 매핑을 정의한다.

정의되지 않은 타입은 직접 정의해야 한다.

## 1. 스칼라
### 1.1. 원시 타입 (Primitive Types)
| Java 타입 | VEL 타입 |
| --- | --- |
| `byte` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}8}$ |
| `short` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}16}$ |
| `int` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}32}$ |
| `long` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}64}$ |
| `float` | $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$ |
| `double` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ |
| `boolean` | $\langle\mathrm{Bool}\rangle$ |
| `char` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}^{+}16}$[^1] |

### 1.2. Wrapper Types
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.lang.Byte` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}8}$ |
| `java.lang.Short` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}16}$ |
| `java.lang.Integer` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}32}$ |
| `java.lang.Long` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}64}$ |
| `java.lang.Float` | $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$ |
| `java.lang.Double` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ |
| `java.lang.Boolean` | $\langle\mathrm{Bool}\rangle$ |
| `java.lang.Character` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}^{+}16}$[^1] |

래퍼 타입의 값이 `null`이면 `NullPointerException`을 던진다.

### 1.3. 임의 정밀도
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.math.BigInteger` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}\infty}$ |
| `java.math.BigDecimal` | $\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$ |

$\langle\mathrm{Real}\rangle_{\mathrm{D}\infty}$의 정밀도 처리(VEL 타입 시스템 3.3절 "$\mathrm{D}\infty$의 정밀도")는 다음과 같이 구체화한다.

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

원소가 스칼라 타입 $\langle \mathbb K \rangle_R$(스칼라 체 $\mathbb K$, 해상도 $R$)인 시퀀스에 대해
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
- 모든 성분 체의 모양이 같다: $\mathbf d_1 = \cdots = \mathbf d_N = \mathbf d$ ($\top$은 VEL 타입 시스템 1.2절을 따른다)
- 모든 성분의 스칼라 체가 같다: $\mathbb K_1 = \cdots = \mathbb K_N = \mathbb K$

$$ T \mapsto \langle\mathrm{Array}(N, \mathbf d, \mathbb K)\rangle_{R_1 \sqcup \cdots \sqcup R_N} $$

즉 성분의 모양 앞에 크기 $N$인 차원을 붙인다. 성분 $i$는 첫 인덱스가 $i$인 부분 $T[i, \cdots]$다. 해상도가 서로 다르면 넓은 쪽($\sqcup$)을 따른다. 위 조건을 만족하지 않는 객체는 배열이 아니다.
- 성분이 스칼라면 $\mathrm{Array}(N) = \mathrm{Vec}(N)$이다.
- 성분이 길이 $M$인 벡터면 $\mathrm{Array}(N, M, 1) = \mathrm{Mat}(N, M)$이다. 성분 $i$는 $i$번째 행이다.
- 성분이 행렬이면 3차원 배열이 된다.

| $T$ | 성분 | VEL 타입 |
| --- | --- | --- |
| `record P(double x, double y)` | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$, $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record Q(int a, double b)` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}32}$, $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Vec}(2, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record R(@Dim(3) double[] u, @Dim(3) double[] v)` | $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$, $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Mat}(2, 3, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record S(double[] u, double[] v)` | $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$, $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$ | $\langle\mathrm{Mat}(2, \top, \mathrm{Real})\rangle_{\mathrm{F}64}$ |
| `record B(int a, boolean b)` | $\langle\mathrm{Real}\rangle_{\mathrm{Z}32}$, $\langle\mathrm{Bool}\rangle$ | 배열이 아님 (스칼라 체가 다름) |

## 3. 메소드와 함수형 인터페이스 (Functional Interfaces)
정적 메소드와 함수형 인터페이스의 유일한 함수의 모든 인자 타입과 반환 타입이 VEL로 변환 가능하다면 VEL의 함수 타입으로 취급한다.

`T` 타입의 동적 메소드 `R foo(U bar)`는 가장 첫 인자를 `T`로 취급해 $T \times U \mapsto R$로 변환한다.

호출 문법은 Java를 따른다.
- 동적 메소드: `t.foo(x)`. `foo(t, x)` 형태로는 부를 수 없다.
- 정적 메소드: `T.foo(x)` (정규화된 이름 `java.lang.Math.sqrt(x)`도 가능)

VEL의 불변성은 VEL 값에만 해당한다. 호출한 Java 메서드가 객체의 상태를 바꾸는 부수 효과는 막지 않는다.

## 4. 이름
VEL은 Java의 지역 변수, 매개변수, 필드를 이름 그대로 참조한다. VEL 식별자는 Java 식별자와 같은 문자 집합을 쓴다. VEL의 예약어는 `return`, `true`, `false`, `if`, `else`, `then`이며, `then`을 뺀 나머지는 Java 예약어이기도 하다. `then`이라는 Java 변수는 `` `then` ``으로 쓴다.

VEL이 미리 선언한 이름은 같은 이름의 Java 변수보다 **우선한다**. Java 변수를 가리키려면 Kotlin처럼 이름을 백틱으로 감싼다.

| VEL 표기 | 의미 |
| --- | --- |
| `j` | 허수 단위 $\mathrm{complex}(0, 1)$ |
| `` `j` `` | Java 변수 `j` |
| `end` (인덱스 안) | 그 차원의 길이 (마지막 인덱스는 `end-1`) |
| `` `end` `` | Java 변수 `end` |

백틱 안에는 Java 식별자만 쓸 수 있다. 미리 선언되지 않은 이름에 백틱을 붙여도 된다(`` `x` ``는 `x`와 같다).

`j`와 `end`는 VEL의 키워드가 아니라 **미리 선언된 변수**다. 대입하면 다른 변수처럼 새 값에 다시 묶인다(예: `j = 3` 이후의 `j`는 3).

## 5. VEL 밖으로 나가는 값
`return` 값이나 Java 변수에 저장되는 값은 대상 Java 타입의 해상도로 변환된다(VEL 타입 시스템 3.2절). 대상의 해상도가 더 낮으면 손실을 허용한다. 예: VEL에서 $\mathrm{D}\infty$로 계산한 결과를 `double`을 반환하는 `@Vel` 메서드에서 돌려주면 `double`로 반올림된다.

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

[^1]: `Character.codePointAt(char[], 0)`를 이용해 코드포인트 값을 취급한다.