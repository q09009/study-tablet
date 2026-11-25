# Study Tablet

강의 들을 때 태블릿에서 쓰는 안드로이드 공부 앱 (Kotlin + Jetpack Compose).

## 기능

- **ChatGPT 로그인 (Codex OAuth)** – Codex CLI와 같은 "Sign in with ChatGPT" PKCE 흐름. ChatGPT 플랜의 Codex 사용량으로 AI 기능을 쓰고, 홈 화면에 사용량(5시간/주간 한도)을 표시합니다.
- **PDF 뷰어** – 50%~500% 확대/축소: 두 손가락 핀치, 더블탭(100% ↔ 250%), 상단 +/− 버튼, 100% 맞춤 버튼. 확대 시 화면 영역을 고해상도로 다시 렌더링. 드래그/플링으로 이동.
- **AI 요약** – PDF 텍스트를 추출해 Codex 모델로 마크다운 요약(핵심 정리, 예상 질문 포함)을 생성·표시·공유. 문서별로 캐시됩니다.
- **AI 질문 대화창** – 문서 화면 상단 말풍선 버튼으로 열고 닫는 채팅 패널. 개념을 바로 물어볼 수 있습니다(같은 ChatGPT 로그인/사용량 사용, 앱 실행 중 대화 유지).
- **녹음** – 문서별 강의 녹음(백그라운드 포그라운드 서비스), 재생/탐색/배속.
- 태블릿(가로 840dp 이상)에서는 PDF | 요약·녹음 분할 화면, 좁은 화면에서는 탭.

## 빌드 & 설치

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

요구사항: JDK 17+, Android SDK 36 (`local.properties`의 `sdk.dir`).

## 릴리스 & 앱 내 업데이트

- 릴리스: `git tag v0.2.0; git push origin v0.2.0` → GitHub Actions(`.github/workflows/release.yml`)가 서명된 APK를 빌드해 GitHub Release에 올립니다. 태그가 버전이 됩니다(`vX.Y.Z` → versionCode `X*10000+Y*100+Z`).
- 앱은 실행 시 공개 저장소의 최신 릴리스를 확인하고, 홈 화면 "앱 업데이트" 카드에서 버튼 한 번으로 다운로드·설치합니다(토큰 불필요).
- 서명 키: `keystore.properties`(gitignore됨) + 키스토어 파일. CI는 `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD` 시크릿을 씁니다. **키스토어를 잃어버리면 기존 설치본에 업데이트할 수 없으니 백업 필수.**
- 디버그 빌드가 설치되어 있으면 서명이 달라 업데이트가 안 됩니다. 처음 한 번은 앱을 지우고 릴리스 APK를 설치하세요.
- 로컬 릴리스 빌드: `.\gradlew.bat assembleRelease "-PappVersion=0.2.0"`

## 참고 / 주의

- 로그인은 기기 안에서 `http://localhost:1455/auth/callback` 로컬 서버로 콜백을 받습니다(Codex CLI와 동일 포트). 로그인 완료 후 브라우저 페이지의 "앱으로 돌아가기"를 누르세요.
- Codex CLI의 공개 OAuth 클라이언트와 ChatGPT Codex 백엔드(`chatgpt.com/backend-api/codex`)를 사용하는 비공식 방식이라 OpenAI 측 변경으로 동작이 바뀔 수 있습니다.
- 모델이 거부되면 홈 화면의 "모델" 칸에서 바꿔보세요(예: `gpt-5`, `gpt-5-codex`).
- 스캔본(이미지) PDF는 텍스트가 없어 요약할 수 없습니다.
- 토큰은 앱 전용 저장소에 보관됩니다.
