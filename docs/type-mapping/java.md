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

$\langle\mathrm{Real}\rangle_D(\infty)$ 연산의 기본 정밀도 문맥은 `java.math.MathContext.DECIMAL128`(유효숫자 34자리, `HALF_EVEN`)이다.
이후 `@VelConfig`로 바꿀 수 있게 한다.

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