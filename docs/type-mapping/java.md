## Java와 VEL의 타입 매핑
Java 타입 시스템 및 SDK에서 정의된 라이브러리와의 기본 매핑을 정의한다.

정의되지 않은 타입은 직접 정의해야 한다.

## 1. 스칼라
### 1.1. 원시 타입 (Primitive Types)
| Java 타입 | VEL 타입 |
| --- | --- |
| `byte` | $\langle\mathrm{Real}\rangle_Z(8)$ |
| `short` | $\langle\mathrm{Real}\rangle_Z(16)$ |
| `int` | $\langle\mathrm{Real}\rangle_Z(32)$ |
| `long` | $\langle\mathrm{Real}\rangle_Z(64)$ |
| `float` | $\langle\mathrm{Real}\rangle_F(32)$ |
| `double` | $\langle\mathrm{Real}\rangle_F(64)$ |
| `boolean` | $\langle\mathrm{Bool}\rangle$ |
| `char` | $\langle\mathrm{Real}\rangle_{Z+}(16)$[^1] |

### 1.2. Wrapper Types
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.lang.Byte` | $\langle\mathrm{Real}\rangle_Z(8)$ |
| `java.lang.Short` | $\langle\mathrm{Real}\rangle_Z(16)$ |
| `java.lang.Integer` | $\langle\mathrm{Real}\rangle_Z(32)$ |
| `java.lang.Long` | $\langle\mathrm{Real}\rangle_Z(64)$ |
| `java.lang.Float` | $\langle\mathrm{Real}\rangle_F(32)$ |
| `java.lang.Double` | $\langle\mathrm{Real}\rangle_F(64)$ |
| `java.lang.Boolean` | $\langle\mathrm{Bool}\rangle$ |
| `java.lang.Character` | $\langle\mathrm{Real}\rangle_{Z+}(16)$[^1] |

### 1.3. 임의 정밀도
| Java 타입 | VEL 타입 |
| --- | --- |
| `java.math.BigInteger` | $\langle\mathrm{Real}\rangle_Z(\infty)$ |
| `java.math.BigDecimal` | $\langle\mathrm{Real}\rangle_D(\infty)$ |

$\langle\mathrm{Real}\rangle_D(\infty)$의 정밀도 처리(VEL 타입 시스템 3.3절 "$D(\infty)$의 정밀도")는 다음과 같이 구체화한다.

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

## 2. 시퀀스
이 문단에서 "시퀀스"는 다음 중 하나를 말하며, `a[]`로 표기한다.
- Java 배열(`int[]`)
- `java.util.List`(`List<Integer>`)

스칼라 타입 $F$에 대해
- 1차원 시퀀스 $F[n]$는 $\mathrm{Vec}(n,F)$로 취급한다.
- 2차원 시퀀스 $F[m][n]$는 $\mathrm{Mat}(n,m,F)$로 취급한다.

3차원 이상 시퀀스에 대해서도, 각 차원의 크기를 역순으로 놓은 배열로 취급한다.

## 3. 메소드와 함수형 인터페이스 (Functional Interfaces)
정적 메소드와 함수형 인터페이스의 유일한 함수의 모든 인자 타입과 반환 타입이 VEL로 변환 가능하다면 VEL의 함수 타입으로 취급한다.

`T` 타입의 동적 메소드 `R foo(U bar)`는 가장 첫 인자를 `T`로 취급해 $T \times U \mapsto R$로 변환한다.

[^1]: `Character.codePointAt(char[], 0)`를 이용해 코드포인트 값을 취급한다.