# VEL 타입 시스템
## 0. 용어
### 0.1. 서수 (Cardinal)
본 문서에서 **서수**(Cardinal, $\mathrm{Card}$)는 최소 원소 $0$, 자연수, 그리고 최대 원소 $\top$ 으로 구성된 타입이다.
$$ \mathrm{Card} := 0 \mid \mathbb{N} \mid \top $$
다음과 같이 순서를 가진다.
$$ 0 < 1 < 2 < \cdots < \top$$

## 1. 타입의 구성
VEL의 모든 객체는 **함수**로 취급한다. 즉 VEL에서 함수는 일급(first-class) 객체다.
$$ X <: A \mapsto B$$

**배열**은 스칼라 타입 $F$가 정해졌을 때, 1개 이상의 서수에 따라 스칼라 값이 정해지는 함수다.
$$ \mathrm{Array}(d_1,d_2,\cdots,d_n,F) := \prod [0,d_i) \mapsto F \quad (d_i<:\mathrm{Card})$$
인덱스가 0부터 시작함에 유의하라. 스칼라 타입 $F$ 에 대해서는 후술한다.

**행렬**은 랭크 2의 배열이다.
$$ \mathrm{Mat}(m,n,F) := \mathrm{Array}(m,n,F)$$

**벡터**는 열벡터로 표현한다.
$$ \mathrm{Vec}(n, F) := \mathrm{Mat}(n, 1, F) $$

**스칼라**는 자신의 체에 대해 $1 \times 1$ 행렬로 취급한다.
$$ [F]:=\mathrm{Mat}(1,1,F) $$

### 1.1. 스칼라 타입
스칼라 타입은 VEL의 원자적 타입이며, 연산을 결정하는 수학적 체의 역할도 수행한다.
  - Boolean $\mathrm{Bool}$: 부울 대수[^1]
  - Real $\mathrm{Real}$: 실수
  - Complex $\mathrm{Complex}$: 복소수

아래와 같은 자명한 위계를 갖는다.
$$\mathrm{Real} <: \mathrm{Complex}$$
Boolean과 Real 사이에는 명시적인 위계를 갖지 않는다.

## 2. 해상도 (Resolution)
원소를 표현할 수 있는 범위(range)와 정확도(precision)의 그룹.

프로그래밍 언어, 또는 외부 라이브러리와의 추상/구체화를 위해 둔다.
  - Boolean: Boolean Literal $\langle\mathrm{Bool}\rangle$
  - Real, Complex
    - Signed $n$-bit Integer $\langle\mathbb K\rangle_{Z}(n)$
    - Unsigned $n$-bit Integer $\langle\mathbb K\rangle_{Z+}(n)$
    - Signed Arbitrary Integer $\langle\mathbb K\rangle_{Z}(\infty)$
    - IEEE 754 Binary $n$-bit Float Number $\langle\mathbb K\rangle_{F}(n)$
    - IEEE 754 Decimal $n$-bit Float Number $\langle\mathbb K\rangle_{D}(n)$
    - Arbitrary Decimal $\langle\mathbb K\rangle_{D}(\infty)$

구체적인 매핑은 `./type-mapping`에 둔다.

## 3. 해상도 승격 (Promotion)
이 장에서 $Z(n)$, $Z^+(n)$, $F(n)$, $D(\infty)$는 각각 $\langle\mathbb K\rangle_{Z}(n)$, $\langle\mathbb K\rangle_{Z+}(n)$, $\langle\mathbb K\rangle_{F}(n)$, $\langle\mathbb K\rangle_{D}(\infty)$의 줄임이다.
스칼라 타입(체) $\mathbb K$와 해상도는 독립적으로 승격한다.

### 3.1. 체의 승격
이항 연산의 체는 두 피연산자 체의 상한(join)이다. 즉 $\mathrm{Real} \vee \mathrm{Complex} = \mathrm{Complex}$.

$\mathrm{Bool}$은 다른 체와 상한을 갖지 않으므로, $\mathrm{Bool}$과 $\mathrm{Real}$/$\mathrm{Complex}$ 사이의 연산은 타입 오류다.

### 3.2. 표현 순서
해상도 $A$의 모든 값을 해상도 $B$가 **정확히** 표현할 수 있을 때 $A \sqsubseteq B$로 쓴다.

| 관계 | 조건 |
| --- | --- |
| $Z(a) \sqsubseteq Z(b)$ | $a \le b$ |
| $Z^+(a) \sqsubseteq Z^+(b)$ | $a \le b$ |
| $Z^+(a) \sqsubseteq Z(b)$ | $a < b$ |
| $Z(a),\ Z^+(a) \sqsubseteq Z(\infty)$ | 항상 |
| $F(a) \sqsubseteq F(b)$ | $a \le b$ |
| $Z(a) \sqsubseteq F(b)$ | $a - 1 \le p(b)$ ($p(b)$: 가수 비트 수 + 1. $p(32)=24$, $p(64)=53$) |
| $Z^+(a) \sqsubseteq F(b)$ | $a \le p(b)$ |
| $Z(\infty),\ F(a) \sqsubseteq D(\infty)$ | 항상 ($F$의 NaN, $\pm\infty$ 제외) |

$Z(a) \sqsubseteq Z^+(b)$는 성립하지 않는다.

$\sqsubseteq$는 **암시적 변환이 허용되는 방향**이다. 대입·인자 전달에서 $\sqsubseteq$를 만족하지 않는 변환은 명시적 변환을 요구한다.

### 3.3. 이항 산술 연산의 결과 해상도
피연산자 해상도 $A$, $B$에 대해 결과 해상도 $A \sqcup B$는 아래 표에서 **위에서부터 처음 맞는 행**으로 정한다.

| # | 조건 | 결과 |
| --- | --- | --- |
| 1 | 어느 한쪽이 $D(\infty)$ | $D(\infty)$ |
| 2 | 한쪽이 $Z(\infty)$, 다른 쪽이 $F$ | $D(\infty)$ |
| 3 | 어느 한쪽이 $Z(\infty)$ | $Z(\infty)$ |
| 4 | 어느 한쪽이 $F$ | $F(\max)$ — 양쪽 $F$의 너비 중 최댓값, 정수 쪽은 무시 |
| 5 | $Z(a)$, $Z(b)$ | $Z(\max(a, b))$ |
| 6 | $Z^+(a)$, $Z^+(b)$ | $Z^+(\max(a, b))$ |
| 7 | $Z(a)$, $Z^+(b)$ | $Z(\max(a, b + 1))$ |

규칙 4는 정확성보다 성능을 택한 규칙이다. 예를 들어 $Z(64) \sqcup F(32) = F(32)$이며, 정밀도 손실이 있을 수 있다. 이는 Java의 이진 수치 승격과 같다.

#### 최소 연산 폭
정수 결과(규칙 5–7)의 폭은 최소 32비트로 올린다. 즉 $Z(8) + Z(8) = Z(32)$.

- 바이트·문자 데이터를 누적할 때 생기는 의도치 않은 오버플로를 줄인다.
- Java의 정수 승격 규칙과 일치하므로 생성 코드에 축소 변환(cast)이 필요 없다.

#### 연산자별 예외
| 연산 | 결과 |
| --- | --- |
| 단항 $-$ on $Z^+(n)$ | $Z(n+1)$ (최소 연산 폭 적용) |
| $Z^+(a) - Z^+(b)$ | $Z(\max(a, b) + 1)$ (최소 연산 폭 적용) |
| 정수 $/$ 정수 | $F(64)$ (참 나눗셈). $Z(\infty)$가 포함되면 $D(\infty)$ |
| $A$ `^` 음이 아닌 정수 리터럴 | $A$ (최소 연산 폭 적용) |
| $A$ `^` 그 외 | $A \sqcup F(64)$ |
| `%` | 정수끼리는 정수 결과($\sqcup$ 규칙), 그 외는 $\sqcup$ 규칙 |

#### 성분별 연산과 행렬 연산
성분별 연산자는 대응하는 연산자와 같은 해상도 규칙을 원소마다 적용한다.

| 성분별 | 따르는 규칙 |
| --- | --- |
| `.+` `.-` | `+` `-` (별칭) |
| `.*` | `*` |
| `./` | `/` |
| `.^` | `^` |

행렬 연산의 결과 원소 해상도는 다음과 같다.
- **`*` (행렬 곱)**: 원소의 곱과 합만으로 계산하므로 두 원소 해상도의 $\sqcup$를 따른다.
- **`/` (우측 나눗셈, $A/B = AB^{-1}$)**: $B$가 스칼라가 아니면 역행렬이 필요하다. 그래서 $A \sqcup B$에 **정수 $/$ 정수** 규칙을 적용한다.
  - 정수 → $F(64)$
  - $Z(\infty)$ 포함 → $D(\infty)$
  - $F$, $D(\infty)$ → 그대로
  - $D(\infty)$의 역행렬은 사칙연산만으로 구하므로 분수 표현으로 정확하게 계산한다.
- **`^` (행렬 거듭제곱)**: 스칼라 `^`와 같다.
  - 음이 아닌 정수 리터럴 지수는 반복 곱이므로 해상도를 유지한다.
  - 그 외 지수(음의 지수는 역행렬을 포함)는 $A \sqcup F(64)$다.
- **`'`, `.'` (전치)**: 해상도를 바꾸지 않는다.

#### 오버플로
고정 폭 정수 $Z(n)$, $Z^+(n)$의 연산 결과가 범위를 넘으면 2의 보수로 순환(wrap)한다.

#### $D(\infty)$의 정밀도
$D(\infty)$는 **중간 연산에서 반올림 오차를 최대한 누적하지 않고**, **최종 결과에서 필요할 때만 한 번 반올림**한다.

**중간 연산**
- $+$, $-$, $\times$, 정수 지수 거듭제곱: 유한 십진수끼리의 연산이므로 그대로 정확하다.
- $/$: 즉시 반올림하지 않는다. 값을 분수 $p/q$($p, q$는 $D(\infty)$, $q > 0$)로 유지하고, 이후 연산도 분수 연산으로 이어간다.
  - 예: $a/b + c/d = (ad + cb)/(bd)$
  - 이렇게 하면 나눗셈은 최종 결과에서 한 번만 일어난다.
- 비교: 분수끼리 교차 곱으로 비교하므로 반올림 없이 정확하다.
- 정확히 표현할 수 없는 연산(정수가 아닌 지수, 이후 추가될 $\sqrt{\ }$·초월 함수 등): 중간 반올림이 불가피하다.
  - 이때는 최종 정밀도보다 높은 **작업 정밀도**로 계산한다.
  - 중간 반올림이 일어나는 경우는 이것뿐이다.

**최종 결과**
값이 VEL 밖으로 나가는 시점을 최종 결과로 본다.
- `return`
- Java 쪽 변수·필드에 대입
- 외부 함수의 인자로 전달
- 다른 해상도로 변환

VEL 내부 지역 변수에 대입하는 것은 중간 연산이다.

최종 결과에서는 다음과 같이 처리한다.
- 분수 $p/q$가 유한 십진수로 나누어떨어지면 **반올림 없이 정확한 값**을 낸다.
- 그렇지 않을 때만(예: $1/3$) **최종 정밀도 문맥**으로 반올림한다.

최종 정밀도 문맥과 작업 정밀도의 기본값은 대상 언어 매핑에서 정하며, 설정(`@VelConfig`)으로 바꿀 수 있다.

분수 표현은 연산이 이어질수록 분자·분모의 자릿수가 커질 수 있다. 반복 누적에서는 정확도를 얻는 대신 비용이 든다.

### 3.4. 리터럴
리터럴은 **미정 해상도**를 가지며, 함께 쓰인 피연산자의 해상도를 따른다.
- 정수 리터럴은 상대 해상도가 그 값을 정확히 표현할 수 있으면 그 해상도를 따른다. 예: `0.5 * x`에서 `x`가 $F(32)$이면 `0.5`도 $F(32)$.
- 소수 리터럴은 상대가 $F$ 또는 $D$일 때만 그 해상도를 따른다.
- 따를 상대가 없거나 표현할 수 없으면 기본 해상도를 쓴다.
  - 정수 리터럴: $Z(32)$, 범위를 넘으면 $Z(64)$, 그것도 넘으면 $Z(\infty)$
  - 소수 리터럴: $F(64)$

### 3.5. 배열
배열 간 원소별 연산의 원소 해상도는 위 규칙을 원소 해상도에 적용해 정한다.
$$ \mathrm{Array}(\mathbf d, \langle\mathbb K_1\rangle_A) \circ \mathrm{Array}(\mathbf d, \langle\mathbb K_2\rangle_B) : \mathrm{Array}(\mathbf d, \langle\mathbb K_1 \vee \mathbb K_2\rangle_{A \sqcup B}) $$

[^1]: 엄밀히 수학적으로는 체(field)가 아니지만 VEL에서는 스칼라 타입으로 취급한다. Boolean 체에서는 $+$ 연산을 OR로, $\cdot$ 연산을 AND로 적용한다.