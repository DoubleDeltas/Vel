# VEL 타입 시스템
## 0. 용어
### 0.1. 서수 (Cardinal)
본 문서에서 **서수**(Cardinal, $\mathrm{Card}$)는 최소 원소 $0$, 자연수, 그리고 최대 원소 $\top$ 으로 구성된 집합이다.
$$ \mathrm{Card} := 0 \mid \mathbb{N} \mid \top $$
다음과 같이 순서를 가진다.
$$ 0 < 1 < 2 < \cdots < \top$$

### 0.2. 대수, 해상도, 타입
- **대수**(algebra): 값이 속하는 수학적 구조다. 값을 어떻게 표현할지(해상도)는 정하지 않는다. 스칼라 대수와, 스칼라 대수를 원소로 하는 배열(벡터, 행렬 등)이 대수다. 예: $\mathrm{Real}$, $\mathrm{Complex}$, $\mathrm{Vec}(3, \mathrm{Real})$, $\mathrm{Mat}(m, n, \mathrm{Complex})$
- **스칼라 대수**(scalar algebra): 원자적인 대수다. $\mathrm{Bool}$, $\mathrm{Int}$, $\mathrm{Real}$, $\mathrm{Complex}$ 네 가지가 있다(1.1절).
- **해상도**(resolution): 원소를 표현하는 범위와 정밀도다. 예: $\mathrm{Z}32$, $\mathrm{F}64$, $\mathrm{D}\infty$ (2장)
- **타입**(type): 대수에 해상도를 적용한 것이다. 대수 $\mathbb K$에 해상도 $R$을 적용한 타입을 $\langle \mathbb K \rangle_R$로 쓴다. VEL의 값은 타입을 가지며, **타입이 Java/Kotlin의 타입과 대응한다.**
- **스칼라 타입**(scalar type): 스칼라 대수에 해상도를 적용한 타입이다. 예: $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$(Java `double`), $\langle\mathrm{Bool}\rangle$(Java `boolean`). 배열 타입 $\langle\mathrm{Array}(\mathbf d, \mathbb K)\rangle_R$의 원소는 스칼라 타입 $\langle\mathbb K\rangle_R$이다.

| 대수 | 타입 | Java 타입 |
| --- | --- | --- |
| $\mathrm{Real}$ | $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$ | `double` |
| $\mathrm{Int}$ | $\langle\mathrm{Int}\rangle_{\mathrm{Z}32}$ | `int` |
| $\mathrm{Bool}$ | $\langle\mathrm{Bool}\rangle$ | `boolean` |
| $\mathrm{Vec}(\top, \mathrm{Real})$ | $\langle\mathrm{Vec}(\top, \mathrm{Real})\rangle_{\mathrm{F}64}$ | `double[]` |

1장은 대수(값의 모양과 수학적 구조)를, 2장과 3장은 해상도를 다룬다. 1장의 모양 규칙은 타입에도 그대로 적용된다.

## 1. 대수의 구성
VEL의 모든 객체는 **함수**로 취급한다. 즉 VEL에서 함수는 일급(first-class) 객체다.
$$ X <: A \mapsto B$$

**배열**은 스칼라 대수 $\mathbb K$가 정해졌을 때, 1개 이상의 서수에 따라 스칼라 값이 정해지는 함수다.
$$ \mathrm{Array}(d_1,d_2,\cdots,d_n,\mathbb K) := \prod [0,d_i) \mapsto \mathbb K \quad (d_i<:\mathrm{Card})$$
인덱스가 0부터 시작함에 유의하라. 스칼라 대수 $\mathbb K$에 대해서는 후술한다.

**행렬**은 랭크 2의 배열이다.
$$ \mathrm{Mat}(m,n,\mathbb K) := \mathrm{Array}(m,n,\mathbb K)$$

**벡터**는 열벡터로 표현한다.
$$ \mathrm{Vec}(n, \mathbb K) := \mathrm{Mat}(n, 1, \mathbb K) $$

**스칼라**는 자신의 스칼라 대수에 대해 $1 \times 1$ 행렬로 취급한다.
$$ [\mathbb K]:=\mathrm{Mat}(1,1,\mathbb K) $$

### 1.1. 스칼라 대수
스칼라 대수는 VEL의 원자적인 대수이며, 연산의 의미를 정한다.
  - Boolean $\mathrm{Bool}$: 부울 대수[^1]
  - Int $\mathrm{Int}$: 정수
  - Real $\mathrm{Real}$: 실수
  - Complex $\mathrm{Complex}$: 복소수

수치 대수는 아래와 같은 자명한 위계를 갖는다.
$$\mathrm{Int} <: \mathrm{Real} <: \mathrm{Complex}$$
$\mathrm{Bool}$은 다른 스칼라 대수와 위계를 갖지 않는다. $\mathrm{Bool}$의 $+$는 OR이므로 $\mathrm{Bool}$을 0과 1인 정수로 볼 수 없다.

복소 정수(가우스 정수)는 지금은 다루지 않는다. 필요해지면 별도의 스칼라 대수로 추가한다.

### 1.2. 크기가 $\top$인 차원
배열의 각 차원 크기 $d_i$는 서수이므로 $\top$일 수 있다. $\top$은 **컴파일 타임에 크기를 모르는 차원**을 뜻한다. 실제 길이는 실행 시점에 정해진다.

서수의 덧셈과 최댓값은 다음과 같이 확장한다($n$은 자연수).
$$ n + \top = \top + n = \top, \qquad \max(n, \top) = \top $$

크기가 $\top$인 차원에는 다음 규칙을 적용한다.
- **범위 밖 원소는 null이다.** 실제 길이 이상의 인덱스에 있는 원소는 값이 없는 것(null)으로 취급한다.
- **null 원소를 연산에 쓰면 `NullPointerException`이 발생한다.** 크기를 맞추기 위한 별도의 런타임 검사 코드는 생성하지 않는다. 크기 불일치는 이 예외로 드러난다.
- 크기가 $\top$인 차원끼리, 또는 $\top$과 $n$이 원소별로 연산할 때 결과 차원의 크기는 $\top$이다. 실제 길이는 피연산자 길이의 최댓값이다. 짧은 쪽은 범위 밖 원소가 null이 되므로, 길이가 다르면 `NullPointerException`이 발생한다.

크기가 정해진 차원($n$)끼리 맞지 않으면 컴파일 오류다.

### 1.3. 브로드캐스팅
원소별 연산(`+` `-` `.+` `.-` `.*` `./` `.^` `//` `///` `%%` `%%%` `o+`)은 MATLAB의 암시적 확장처럼 피연산자의 모양이 달라도 브로드캐스팅한다.

- 스칼라($1 \times 1$)는 다른 피연산자의 모양으로 확장된다.
- 두 배열은 차원별로 비교한다. 랭크가 다르면 짧은 쪽 뒤에 크기 1인 차원을 붙여 맞춘다.
  - 크기가 같으면 그대로 둔다.
  - 한쪽 크기가 **컴파일 타임에 1로 알려져 있으면** 다른 쪽 크기로 확장한다.
  - 그 외에는 1.2절의 규칙을 따른다(크기가 정해져 있으면 컴파일 오류, $\top$이 포함되면 결과 크기 $\top$).
- 크기가 $\top$인 차원은 실행 시점에 길이가 1이더라도 **확장하지 않는다.** 확장 여부는 컴파일 타임에 정해진다.

예: $\mathrm{Vec}(3) + \mathrm{Mat}(1, 4) : \mathrm{Mat}(3, 4)$ (열벡터 + 행벡터), $\mathrm{Mat}(3,4) + [\mathbb K] : \mathrm{Mat}(3,4)$

행렬 곱 `*`은 브로드캐스팅하지 않는다. $\mathrm{Mat}(m,k) \cdot \mathrm{Mat}(k,n) : \mathrm{Mat}(m,n)$이며, 스칼라와의 곱은 원소별 곱이다.

### 1.4. 연결 (블록 행렬)
행렬 리터럴의 원소는 행렬일 수 있다. `,`는 가로 연결, `;`는 세로 연결이다.
$$ [A, B] : \mathrm{Mat}(a, c + d) \quad (A : \mathrm{Mat}(a, c),\ B : \mathrm{Mat}(a, d)) $$
$$ [A; C] : \mathrm{Mat}(a + b, c) \quad (A : \mathrm{Mat}(a, c),\ C : \mathrm{Mat}(b, c)) $$

따라서 $A : a \times c$, $B : a \times d$, $C : b \times c$, $D : b \times d$에 대해 블록 행렬 $[A, B; C, D]$는 $(a+b) \times (c+d)$ 행렬이다.
- 스칼라는 $1 \times 1$ 행렬이므로 `[1, 2; 3, 4]`는 이 규칙의 특수한 경우다.
- 가로로 연결하는 블록끼리 행 수가, 세로로 연결하는 블록끼리 열 수가 맞아야 한다. 맞는지 판정할 수 없는 $\top$ 차원은 1.2절의 규칙을 따른다.
- 튜플 $(A_1, A_2, \cdots, A_k)$는 연결이 아니라 **쌓기**다. 모든 항목의 모양이 $\mathbf d$로 같아야 하며($\top$는 1.2절), 결과는 $\mathrm{Array}(k, \mathbf d)$, 항목 $i$는 $T[i, \cdots]$다. 원소 해상도는 모든 항목 해상도의 $\sqcup$이다.
  - 스칼라 튜플 $(x, y, z)$는 $\mathrm{Vec}(3)$이다(세로 연결과 같다).
  - 길이 $M$인 벡터 튜플 $(\mathbf u, \mathbf v)$는 $\mathrm{Mat}(2, M)$이다. 항목 $i$가 $i$번째 행이다.
  - 같은 모양의 행렬 튜플은 3차원 배열이 된다.
  - Java/Kotlin의 성분 객체(`record`, `Pair` 등)도 같은 규칙으로 배열이 된다(Java 매핑 2.5절).
- 원소 해상도는 모든 블록 해상도의 $\sqcup$이다(3.3절).

### 1.5. 불변성
VEL의 모든 값은 **불변**이다. 값을 바꾸는 연산은 없고, 바뀐 새 값을 만들 뿐이다.

인덱스 대입과 부분행렬 대입은 MATLAB과 같이 **복사 후 다시 쓰기**로 정의한다.
$$ \mathbf m[i] = x \quad\equiv\quad \mathbf m \leftarrow \mathbf m' \quad (\mathbf m'[i] = x,\ \text{그 외 원소는 } \mathbf m \text{과 같음}) $$

즉 `m[i] = x`는 `m`을 복사해 원소 하나를 바꾼 새 값을 만들고, 이름 `m`을 그 값에 다시 묶는다. 대입 전 `m`의 값을 가리키던 다른 이름은 영향을 받지 않는다.

```
a = (1, 2, 3)
b = a
b[0] = 9      # b = (9, 2, 3), a는 여전히 (1, 2, 3)
```

이 규칙은 의미를 정의할 뿐이며, 원래 값을 더 이상 아무도 참조하지 않으면 컴파일러는 복사를 생략하고 그 자리에서 고칠 수 있다.

대입하는 값은 선택한 영역에 **대입 가능**해야 한다.
- 모양: 선택한 영역과 같거나, 브로드캐스팅으로 영역에 맞출 수 있어야 한다(예: `m[0, :] = 0`).
- 원소: 값과 배열 원소의 스칼라 대수에 상한($\vee$)이 있어야 하고, 해상도는 $\sqcup$를 따른다. `Bool`과 수치는 섞을 수 없다.
- 값의 해상도가 더 크면 **배열 전체가 커진 해상도를 따른다.** 예: $\mathrm{Z}32$ 배열에 `m[0, 0] = 0.5`를 하면 `m`은 $\mathrm{F}64$ 배열이 된다.

**구조 분해 대입** `(x, y, z) = v`, `[a, b; c, d] = m`은 값을 앞 차원부터 나눠 대상에 묶는다. 튜플 패턴(대상 $k$개)은 첫 차원이 $k$인 값을, 행렬 패턴($r$행 $c$열)은 앞의 두 차원이 $r \times c$인 값을 받으며($\top$는 1.2절), 각 대상은 남은 차원을 받는다(1.7절). 스칼라 튜플이나 행렬이면 각 대상은 스칼라다. 버리는 자리 `?`도 모양에는 포함된다.

### 1.6. 범위와 인덱싱
**범위** `a:b`는 끝을 포함하지 않는 구간 $[a, b)$의 인덱스로 이루어진 행벡터다(Python과 같음).
- 경계 $a$, $b$는 서수($\mathrm{Int}$ 스칼라이며 0 이상)여야 한다. 인덱스 안의 `end`는 그 차원의 **길이**다. 그래서 `a:end`는 끝까지이고, 마지막 원소의 인덱스는 `end-1`이다.
- 길이는 두 경계가 모두 정수 리터럴이면 $\max(0, b - a)$이고, 그 외(변수, `end`)에는 $\top$이다.
- 증분 형태는 없다.

**인덱싱** `m[s₁, …, sₖ]`의 각 첨자는 다음 중 하나다.
| 첨자 | 선택 | 결과 차원 크기 |
| --- | --- | --- |
| 정수 스칼라 `i` | 한 위치 | 1 |
| 범위 `a:b` | 범위의 각 위치 | 범위의 길이 |
| `:` | 차원 전체 | 그 차원의 크기 |

- 첨자의 개수는 배열의 랭크와 같아야 한다. 단, 벡터는 첨자 하나로 긴 쪽 차원을 고르며 방향(열/행)을 유지한다.
- 리터럴 인덱스가 크기가 정해진 차원의 범위를 벗어나면 컴파일 오류다. 크기가 $\top$인 차원은 1.2절을 따른다.

### 1.7. 분할과 블록 텐서
$m \times n$ 행렬 $M$과 분할점 $0 \le a_0 < a_1 < \cdots < a_k \le m$, $0 \le b_0 < b_1 < \cdots < b_l \le n$에 대해, 첨자 자리의 분할점 체인 `M[a₀:…:aₖ, b₀:…:bₗ]`은 블록
$$ B_{ij} = M[a_i : a_{i+1},\ b_j : b_{j+1}] \quad (0 \le i < k,\ 0 \le j < l) $$
로 이루어진 블록 행렬이다. 범위가 끝을 포함하지 않으므로 블록은 서로 겹치지 않는다. 범위 `a:b`는 블록이 하나인 분할이다.

분할한 인덱싱의 타입은 블록을 튜플처럼 쌓은(1.4절) **블록 텐서**다. 블록 번호가 앞 차원이다.
$$ M[\mathbf a, \mathbf b] : \mathrm{Array}(k, l, h, w, \mathbb K) $$
- $k$, $l$은 행 블록 수와 열 블록 수다. 블록 $(i, j)$는 $T[i, j, :, :]$다.
- 행만 분할하면 $\mathrm{Array}(k, h, w)$(블록 $i$는 $T[i, :, :]$), 열만 분할하면 $\mathrm{Array}(l, h, w)$다.
- $h$, $w$는 블록의 높이와 너비다. 블록 크기가 모두 같고 컴파일 타임에 알려져 있으면 그 크기, 아니면 $\top$이다. 크기가 다른 블록의 남는 자리는 null이다(1.2절).
- 리터럴 분할점은 컴파일 타임에 검사한다: 순증가, 마지막 점 $\le$ 차원 크기.
- 분할한 인덱스에는 대입할 수 없다.

**구조 분해**는 값을 앞 차원부터 나눈다.
- 튜플 패턴(대상 $k$개): 첫 차원이 $k$여야 하며, 각 대상은 나머지 차원 $V[i, \cdots]$를 받는다. 튜플과 성분 객체를 되돌린다. 예: $\mathrm{Vec}(k)$는 스칼라, $\mathrm{Mat}(k, n)$은 행 $\mathrm{Vec}(n)$, 행만 분할한 블록 텐서는 블록.
- 행렬 패턴($r \times c$): 앞의 두 차원이 $r \times c$여야 하며, 각 대상은 나머지 차원 $V[i, j, \cdots]$를 받는다. 예: 행렬은 원소(스칼라), 블록 텐서 $\mathrm{Array}(k, l, h, w)$는 블록 $\mathrm{Mat}(h, w)$.
- 오른쪽이 분할한 인덱싱 식 그대로이면, 각 대상은 자기 블록의 정확한 크기 $\mathrm{Mat}(a_{i+1} - a_i,\ b_{j+1} - b_j)$를 받는다.

### 1.8. 조건식
`if c then a else b`에서 조건 $c$는 $\mathrm{Bool}$ 스칼라이며, 고른 쪽 가지만 평가한다. 결과 타입은 두 가지 타입의 공통 타입이다.
- 모양: 두 가지의 차원 크기가 같아야 한다. $\top$이 섞이면 결과 크기는 $\top$이다(1.2절).
- 원소: 스칼라 대수는 $\vee$, 해상도는 $\sqcup$(3.3절)를 따른다. 예: $\mathrm{Z}32$와 $\mathrm{F}32$ 가지의 결과는 $\mathrm{F}32$다. $\mathrm{Bool}$과 수치 가지는 섞을 수 없다.

## 2. 해상도 (Resolution)
원소를 표현할 수 있는 범위(range)와 정확도(precision)의 그룹.

프로그래밍 언어, 또는 외부 라이브러리와의 추상/구체화를 위해 둔다.

해상도는 종류와 비트 수를 묶은 하나의 기호로 쓴다. 예를 들어 $\mathrm{F}64$는 "64비트 이진 부동소수"로 읽는다.

| 해상도 | 기호 | 예 |
| --- | --- | --- |
| Boolean | (해상도 하나뿐, $\langle\mathrm{Bool}\rangle$) | |
| 부호 있는 $n$비트 정수 | $\mathrm{Z}n$ | $\mathrm{Z}32$ |
| 부호 없는 $n$비트 정수 | $\mathrm{Z}^{+}n$ | $\mathrm{Z}^{+}16$ |
| 부호 있는 임의 정밀도 정수 | $\mathrm{Z}\infty$ | |
| IEEE 754 이진 $n$비트 부동소수 | $\mathrm{F}n$ | $\mathrm{F}64$ |
| IEEE 754 십진 $n$비트 부동소수 | $\mathrm{D}n$ | $\mathrm{D}64$ |
| 임의 정밀도 십진수 | $\mathrm{D}\infty$ | |

비트 수가 식이면 대괄호로 묶는다. 예: $\mathrm{Z}[\max(a, b) + 1]$

대수 $\mathbb K$에 해상도 $R$을 적용한 **타입**은 $\langle \mathbb K \rangle_R$로 쓴다(0.2절). 예: $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$(Java `double`), $\langle\mathrm{Complex}\rangle_{\mathrm{F}32}$. 해상도만 따로 다룰 때는 $\mathrm{F}64$처럼 기호만 쓴다.

대수가 배열이면 해상도는 그 원소인 스칼라 대수마다 적용된다.
$$ \langle \mathrm{Array}(\mathbf d, \mathbb K) \rangle_R = \mathrm{Array}(\mathbf d, \langle\mathbb K\rangle_R) $$
예: 타입 $\langle\mathrm{Vec}(3, \mathrm{Real})\rangle_{\mathrm{F}64}$는 원소가 64비트 부동소수인 실수 3차원 벡터다(Java `@Dim(3) double[]`). 해상도 없이 쓴 $\mathrm{Vec}(3, \mathrm{Real})$은 타입이 아니라 대수다.

스칼라 대수와 해상도의 조합은 다음으로 제한한다.

| 스칼라 대수 | 해상도 |
| --- | --- |
| $\mathrm{Bool}$ | (없음) |
| $\mathrm{Int}$ | $\mathrm{Z}n$, $\mathrm{Z}^{+}n$, $\mathrm{Z}\infty$ |
| $\mathrm{Real}$, $\mathrm{Complex}$ | $\mathrm{F}n$, $\mathrm{D}n$, $\mathrm{D}\infty$ |

즉 정수 해상도 $\mathrm{Z}n$, $\mathrm{Z}^{+}n$, $\mathrm{Z}\infty$는 $\mathrm{Int}$ 전용이다.

Java/Kotlin 타입과의 구체적인 대응은 `./type-mapping`에 둔다.

## 3. 해상도 승격 (Promotion)
스칼라 대수 $\mathbb K$와 해상도는 독립적으로 승격한다. 이 장의 규칙은 해상도만 다루므로 $\mathrm{Z}n$, $\mathrm{F}n$처럼 해상도 기호(2장)로 쓴다.

### 3.1. 스칼라 대수의 승격
이항 연산 결과의 스칼라 대수는 두 피연산자 스칼라 대수의 상한(join)이다. 예: $\mathrm{Int} \vee \mathrm{Real} = \mathrm{Real}$, $\mathrm{Real} \vee \mathrm{Complex} = \mathrm{Complex}$.

$\mathrm{Bool}$은 다른 스칼라 대수와 상한을 갖지 않으므로, $\mathrm{Bool}$과 수치 대수 사이의 연산은 타입 오류다.

스칼라 대수와 해상도를 따로 승격한 뒤, 조합 제한(2장)을 지키도록 맞춘다.
- 결과 스칼라 대수가 $\mathrm{Real}$이나 $\mathrm{Complex}$인데 해상도가 정수 해상도면, 해상도를 $\mathrm{Z}n \to \mathrm{F}64$, $\mathrm{Z}\infty \to \mathrm{D}\infty$로 바꾼다. 예: `j * x`($x : \langle\mathrm{Int}\rangle_{\mathrm{Z}32}$)는 $\langle\mathrm{Complex}\rangle_{\mathrm{F}64}$다.
- 결과 해상도가 정수 해상도가 아닌데 스칼라 대수가 $\mathrm{Int}$면, 스칼라 대수를 $\mathrm{Real}$로 바꾼다. 예: $\mathrm{Int}$끼리의 참 나눗셈 `/`는 $\langle\mathrm{Real}\rangle_{\mathrm{F}64}$다.

### 3.2. 표현 순서
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

$\sqsubseteq$는 VEL 안에서 **암시적 변환이 허용되는 방향**이다.

VEL의 중간 연산은 해상도를 최대한 높은 쪽으로 유지한다. 결과가 **VEL 밖으로 나갈 때**(`return`, Java/Kotlin 변수에 저장) 대상 변수의 해상도가 더 낮으면 그 해상도로 변환하며, 이때의 손실은 허용한다. 변환은 3.3절 "반올림"을 따른다.

이 정책은 이후 `@VelConfig`로 바꿀 수 있게 한다(예: 중간 연산에서도 낮은 해상도를 쓰는 fastmath).

### 3.3. 이항 산술 연산의 결과 해상도
피연산자 해상도 $A$, $B$에 대해 결과 해상도 $A \sqcup B$는 아래 표에서 **위에서부터 처음 맞는 행**으로 정한다.

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

규칙 4, 5는 정확성보다 성능을 택한 규칙이다. **정수와 부동소수가 섞이면 정수의 너비와 관계없이 부동소수로 승격한다.** 부동소수가 둘이면 더 높은 너비를 따른다. 3.2절의 "중간 연산은 해상도를 최대한 높게 유지" 정책도 이 규칙을 바꾸지 않는다.
- 예: $\mathrm{Z}64 \sqcup \mathrm{F}32 = \mathrm{F}32$이며, 정밀도 손실이 있을 수 있다. 이는 Java의 이진 수치 승격과 같다.
- 십진 부동소수 $\mathrm{D}n$도 같은 방식으로 승격하며, 이진 부동소수와 섞이면 십진 쪽을 따른다. 예: $\mathrm{D}32 \sqcup \mathrm{F}64 = \mathrm{D}64$

#### 최소 연산 폭
정수 결과(규칙 6–8)의 폭은 최소 32비트로 올린다. 즉 $\mathrm{Z}8 + \mathrm{Z}8 = \mathrm{Z}32$.

- 바이트·문자 데이터를 누적할 때 생기는 의도치 않은 오버플로를 줄인다.
- Java의 정수 승격 규칙과 일치하므로 생성 코드에 축소 변환(cast)이 필요 없다.

#### 연산자별 예외
| 연산 | 결과 |
| --- | --- |
| 단항 $-$ on $\mathrm{Z}^{+}n$ | $\mathrm{Z}[n+1]$ (최소 연산 폭 적용) |
| $\mathrm{Z}^{+}a - \mathrm{Z}^{+}b$ | $\mathrm{Z}[\max(a, b) + 1]$ (최소 연산 폭 적용) |
| $\mathrm{Int}$ $/$ $\mathrm{Int}$ | 참 나눗셈. 스칼라 대수는 $\mathrm{Real}$, 해상도는 $\mathrm{F}64$($\mathrm{Z}\infty$가 포함되면 $\mathrm{D}\infty$) |
| `o+` (배타적 논리합) | $\mathrm{Bool}$끼리만 정의된다. 결과는 $\langle\mathrm{Bool}
angle$ |
| `//` (내림 몫) | $-\infty$ 방향으로 내린 몫(Python `//`, Java `Math.floorDiv`와 같음). 해상도는 $\sqcup$ 규칙을 따른다(정수끼리는 정수) |
| `///` (버림 몫) | 0 방향으로 버린 몫(Java 정수 `/`와 같음). 해상도는 $\sqcup$ 규칙 |
| $A$ `^` 음이 아닌 정수 리터럴 | $A$ (최소 연산 폭 적용) |
| $A$ `^` $B$ (그 외) | $A \sqcup B \sqcup \mathrm{F}64$. 지수 $B$의 해상도도 반영한다. 예: $\mathrm{Z}32$ `^` $\mathrm{D}64$ $= \mathrm{D}64$ |
| `%%` (내림 나머지) | 제수의 부호를 따른다(Python `%`, Java `Math.floorMod`와 같음). `a // b`와 짝을 이뤄 $a = (a\ /\!/\ b) \cdot b + (a \,\%\%\, b)$가 성립한다. $\sqcup$ 규칙 |
| `%%%` (버림 나머지) | 피제수의 부호를 따른다(Java `%`와 같음). `a /// b`와 짝을 이뤄 $a = (a\ /\!/\!/\ b) \cdot b + (a \,\%\%\%\, b)$가 성립한다. $\sqcup$ 규칙 |

몫과 나머지는 기호의 개수로 반올림 방향을 나타낸다. 기호가 2개(`//`, `%%`)면 $-\infty$ 방향(내림), 3개(`///`, `%%%`)면 0 방향(버림)이다. 기호 1개인 `%`는 없다. Java의 `a % b`는 VEL에서 `a %%% b`로 쓴다.

| | `a // b` | `a %% b` | `a /// b` | `a %%% b` |
| --- | --- | --- | --- | --- |
| $a = -7,\ b = 2$ | $-4$ | $1$ | $-3$ | $-1$ |
| $a = 7,\ b = -2$ | $-4$ | $-1$ | $-3$ | $1$ |

#### 성분별 연산과 행렬 연산
성분별 연산자는 대응하는 연산자와 같은 해상도 규칙을 원소마다 적용한다.

| 성분별 | 따르는 규칙 |
| --- | --- |
| `.+` `.-` | `+` `-` (별칭) |
| `.*` | `*` |
| `./` | `/` |
| `.^` | `^` |

`//`, `///`, `%%`, `%%%`는 행렬에 대해 원래 원소별로 동작한다.

행렬 연산의 결과 원소 해상도는 다음과 같다.
- **`*` (행렬 곱)**: 원소의 곱과 합만으로 계산하므로 두 원소 해상도의 $\sqcup$를 따른다.
- **`/`, `/>` (우측 나눗셈, $A/B = AB^{-1}$)**: 두 기호는 같다. $B$가 스칼라가 아니면 역행렬이 필요하므로 $B$는 정사각(가역) 행렬이어야 한다. 해상도는 $A \sqcup B$에 **정수 $/$ 정수** 규칙을 적용한다.
  - 정수 → $\mathrm{F}64$
  - $\mathrm{Z}\infty$ 포함 → $\mathrm{D}\infty$
  - $\mathrm{F}n$, $\mathrm{D}n$, $\mathrm{D}\infty$ → 그대로
  - $\mathrm{D}\infty$의 역행렬은 사칙연산만으로 구하므로 분수 표현으로 정확하게 계산한다.
- **`/<` (좌측 나눗셈, $A /\!< B = A^{-1}B$)**: $A$는 정사각(가역) 행렬이고 $B$와 행 수가 같아야 한다. 결과는 $\mathrm{Mat}(n, p)$ ($A : n \times n$, $B : n \times p$). $A$가 스칼라면 $B$의 각 원소를 $A$로 나눈다. 해상도는 `/`와 같다.
- **`^` (행렬 거듭제곱)**: 스칼라 `^`와 같다. 지수는 스칼라만 허용한다(행렬 지수 함수 $e^A$는 이후 `exp(A)` 함수로 제공한다).
  - 음이 아닌 정수 리터럴 지수는 반복 곱이므로 해상도를 유지한다.
  - 그 외 지수 $B$(음의 지수는 역행렬을 포함)는 $A \sqcup B \sqcup \mathrm{F}64$다.
- **`'`, `.'` (전치)**: 해상도를 바꾸지 않는다. 랭크 3 이상의 배열은 인덱스 순서를 뒤집는다: $\mathrm{Array}(d_1, \cdots, d_n)' : \mathrm{Array}(d_n, \cdots, d_1)$

$\mathrm{Complex}$의 `//`, `///`, `%%`, `%%%`는 정의하지 않는다. 함수와 사용자 정의 중위 연산자를 정할 때 함께 정의한다.

#### 오버플로
고정 폭 정수 $\mathrm{Z}n$, $\mathrm{Z}^{+}n$의 연산 결과가 범위를 넘으면 2의 보수로 순환(wrap)한다.

#### 반올림
부동소수 연산과 해상도 변환의 반올림, 오버플로는 IEEE 754의 기본 방식을 따른다.
- 반올림: 가장 가까운 값으로, 같은 거리면 짝수 쪽으로(roundTiesToEven)
- 부동소수 오버플로: $\pm\infty$

#### $\mathrm{D}\infty$의 정밀도
$\mathrm{D}\infty$는 **중간 연산에서 반올림 오차를 최대한 누적하지 않고**, **최종 결과에서 필요할 때만 한 번 반올림**한다.

**중간 연산**
- $+$, $-$, $\times$, 정수 지수 거듭제곱: 유한 십진수끼리의 연산이므로 그대로 정확하다.
- $/$: 즉시 반올림하지 않는다. 값을 분수 $p/q$($p, q$는 $\mathrm{D}\infty$, $q > 0$)로 유지하고, 이후 연산도 분수 연산으로 이어간다.
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
리터럴은 **미정 해상도**를 가지며, 함께 쓰인 피연산자의 해상도를 따른다. 정수 리터럴의 스칼라 대수는 $\mathrm{Int}$, 소수 리터럴의 스칼라 대수는 $\mathrm{Real}$이다.
- 정수 리터럴은 상대 해상도가 그 값을 정확히 표현할 수 있으면 그 해상도를 따른다. 예: `2 * x`에서 `x`가 $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$이면 결과는 $\langle\mathrm{Real}\rangle_{\mathrm{F}32}$, `x`가 $\langle\mathrm{Int}\rangle_{\mathrm{Z}8}$이면 `2`도 $\mathrm{Z}8$이다(결과는 최소 연산 폭에 따라 $\mathrm{Z}32$).
- 소수 리터럴은 상대가 $\mathrm{F}n$, $\mathrm{D}n$, $\mathrm{D}\infty$일 때만 그 해상도를 따른다. 예: `0.5 * x`에서 `x`가 $\mathrm{F}32$이면 `0.5`도 $\mathrm{F}32$.
- 따를 상대가 없거나 표현할 수 없으면 기본 해상도를 쓴다.
  - 정수 리터럴: $\mathrm{Z}32$, 범위를 넘으면 $\mathrm{Z}64$, 그것도 넘으면 $\mathrm{Z}\infty$
  - 소수 리터럴: $\mathrm{F}64$

리터럴끼리의 연산은 **정확하게 계산해 리터럴로 남긴다**(상수 접기). 중간 결과의 정밀도를 최대한 유지하기 위해서다.
- 정수 리터럴끼리의 `+` `-` `*`, 몫·나머지, 음이 아닌 정수 지수 거듭제곱은 정수 리터럴이 된다.
- 그 외(참 나눗셈 `/`, 음의 지수, 소수 리터럴이 섞인 경우)는 소수 리터럴이 된다. 유한 소수로 나누어떨어지지 않으면(예: `1/3`) 정확한 분수 리터럴로 남는다.
- 분수 리터럴은 소수 리터럴처럼 상대 피연산자를 따르며, $\mathrm{D}\infty$에서는 분수 그대로 정확히 표현된다.
- 예: `0.5 * 2 * x`($x : \mathrm{F}32$)에서 `0.5 * 2`는 정확히 1이므로 결과는 $\mathrm{F}32$다.

### 3.5. 배열
배열 간 원소별 연산의 원소 해상도는 위 규칙을 원소 해상도에 적용해 정한다.
$$ \mathrm{Array}(\mathbf d, \langle\mathbb K_1\rangle_A) \circ \mathrm{Array}(\mathbf d, \langle\mathbb K_2\rangle_B) : \mathrm{Array}(\mathbf d, \langle\mathbb K_1 \vee \mathbb K_2\rangle_{A \sqcup B}) $$

[^1]: $\mathrm{Bool}$ 대수에서는 $+$ 연산을 OR로, $\cdot$ 연산을 AND로, `o+`($\oplus$) 연산을 XOR로 적용한다.
