# ToyAgent

<div align="center">

**最もシンプルな方法で、AIエージェントの実装を解き明かす —— 1つのインターフェースクラスが、最小構成のエージェントそのもの**

[简体中文](README.md) · [English](README_EN.md) · 日本語

Java 17 · 依存関係ゼロ · 26 の段階的シナリオ · 可視化プレイグラウンド · 5 言語実装

</div>

対応チュートリアル：[AI Agent 通識教程（ai-agent-guide、中国語）](https://github.com/fuzhengwei/ai-agent-guide) · シナリオ 15-22 は [deepseek-harness-java](https://github.com/fuzhengwei/deepseek-harness-java) を参考にしています

## スクリーンショット

**トップ画面**：7 つのテーマアークでグループ化されたシナリオナビ（左）· 3D ラーニングマップと統計（中央）· 解説記事（右）

![ToyAgent トップ画面](docs/images/home-overview.png)

**シナリオ対話**：チャット + 実行トレースのタイムライン + mermaid シーケンス図 —— エージェントの思考の一步一段落すべてが目に見えます

![シナリオ対話とトレース](docs/images/scenario-trace.png)

**解説記事**：各章にチュートリアル記事が付属し、コードブロックは Java / Python / TS / Go / JS の 5 言語で切り替えられます

![5 言語コード切替](docs/images/article-code-langs.png)

## ひとつのコードで全体を理解する

エージェントの魂はたった一つのメソッドです：

```java
public interface Agent {
    String chat(String input);   // ひとこと受け取り、ひとこと返す
}
```

26 のシナリオは 7 つのテーマアークに沿って進みます：基本カーネル（1-4）→ 記憶と知識（5-8、RAG と LLM-Wiki を含む）→ ツールの進化（9-11）→ 協業と派遣（12-14）→ ランタイムとエンジニアリング（15-18、18 番は総合成）→ 人間と AI の協調とセキュリティ（19-22）→ 拡張と形態（23-26）。

## 多言語実装

同じ 26 シナリオと LLM レイヤーを 5 言語で等価に実装。シナリオごとの Mock 教学スクリプトも 1:1 で揃えており、並べて読むのに最適です：

| 言語 | リポジトリ | 実行環境 | 特徴 |
|------|------------|----------|------|
| ☕ Java | **[ToyAgent](https://github.com/fuzhengwei/ToyAgent)**（本リポジトリ） | JDK 17+ | 本体：26 シナリオ + Web プレイグラウンド + HTTP API |
| 🐍 Python | [ToyAgent-Python](https://github.com/fuzhengwei/ToyAgent-Python) | Python 3.10+ | 標準ライブラリのみ、CLI ランナー |
| 🟦 TypeScript | [ToyAgent-Typescript](https://github.com/fuzhengwei/ToyAgent-Typescript) | Node 18+ | 実行時依存ゼロ（devDeps は typescript のみ）、strict モード |
| 🐹 Go | [ToyAgent-Go](https://github.com/fuzhengwei/ToyAgent-Go) | Go 1.22+ | 標準ライブラリのみ、単一バイナリ |
| 🟨 JavaScript | [ToyAgent-JS](https://github.com/fuzhengwei/ToyAgent-JS) | Node 18+ | ネイティブ ESM、ビルド不要 |

> Web プレイグラウンドは Java 版のみ提供。他言語版はシナリオコードそのもの（`list` / `run` / REPL の CLI モード）に焦点を当て、Web 記事内の 5 言語コードサンプルのソースとなっています。

## 26 のシナリオ

| # | シナリオ | クラス | 新しく学ぶこと |
|:---:|------|--------|----------|
| 01 | チャットエージェント | `ChatAgent` | 最小 MVP：モデルへの接続 |
| 02 | プロンプトエンジニアリング | `PromptAgent` | システムプロンプトの 3 層構造 |
| 03 | ReAct ループ | `ReActAgent` | 思考→行動→観察 |
| 04 | ツール呼び出し | `ToolCallAgent` | Function Calling |
| 05 | メモリ | `MemoryAgent` | マルチターン記憶 + コンテキスト圧縮 |
| 06 | 意図ルーティング | `RouterAgent` | 意図認識 + 判断ハブ |
| 09 | MCP プロトコル | `McpAgent` | ツールの標準インターフェース |
| 10 | スキル編成 | `SkillAgent` | Skills L0/L1/L2 |
| 07 | RAG | `RagAgent` | 検索拡張生成 |
| 12 | マルチエージェント | `MultiAgent` | 計画→実行→レビューの協業 |
| 15 | Loop ランタイム | `LoopAgent` | ガード + ヒューズ + フォールバック |
| 16 | ワークフロー状態機械 | `WorkflowAgent` | LangGraph 流のオーケストレーション |
| 18 | フルスタックエージェント | `FullAgent` | ガード+記憶+ReAct+ツール+ヒューズの総合 |
| 08 | LLM-Wiki 知識コンパイル | `WikiAgent` | 知識の一括コンパイルと増分蓄積 |
| 11 | ツールレジストリ | `RegistryAgent` | ToolDefinition：登録/発見/ホット解除 |
| 17 | ReAct ランタイム | `RuntimeAgent` | turn/step 二段ループ + コンテキスト刈り込み |
| 19 | 人間による介入 | `AskAgent` | 質問で一時停止 → 人間が回答 → 再開 |
| 20 | 承認ゲート | `ApprovalAgent` | リスク分級 + 承認保留 + タイムアウト時は拒否 |
| 21 | サンドボックス多層防御 | `SandboxAgent` | ポリシー/ブラックリスト/境界/正規化の 4 層 |
| 22 | イベントソーシング | `EventSourcedAgent` | 追記専用 JSONL + リプレイで状態再構築 |
| 23 | プラグイン機構 | `PluginAgent` | AgentPlugin 契約 + 分離 ClassLoader |
| 25 | CLI エージェント | `CliAgent` | ターミナル REPL + サンドボックス shell |
| 13 | サブエージェント | `SubagentAgent` | spawn（新コンテキスト）/ fork（親セッション継承） |
| 24 | フック | `HooksAgent` | before で傍受/改変 · after でマスキング/監査 |
| 14 | A2A 協業 | `A2AAgent` | Agent Card による発見 + task_id 信封 + 非同期応答 |
| 26 | スケジューリング | `ScheduleAgent` | 単発/繰り返し実行、結果はイベントログに記録 |

各シナリオは `src/main/java/cn/xiaofuge/ai/agent/stepXX/` に 1 ファイル。インターフェースのコメントがそのままチュートリアル、ネストした実装クラスがロジックの全部です。

## クイックスタート

### 方法 1：依存関係ゼロで実行（推奨、Maven 不要）

```bash
cd ToyAgent

# コンパイル（JDK 17+）
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")

# 起動（デフォルトポート 8099、TOY_AGENT_PORT で変更可）
java -cp target/classes cn.xiaofuge.ai.Application
```

プレイグラウンドを開く：<http://localhost:8099>

### 方法 2：Maven

```bash
mvn package
java -jar target/ToyAgent.jar
```

### その他の言語

各言語リポジトリの README にクイックスタートがあります（いずれも clone するだけで実行可能）：

```bash
# Python: python3 main.py run step03 "北京の天気は"
# TypeScript: npm run build && node dist/main.js run step03 "..."
# Go: go build -o toyagent && ./toyagent run step03 "..."
# JavaScript: node src/main.js run step03 "..."
```

### 実際の LLM に接続（任意）

設定テンプレートをコピーし、OpenAI 互換 API（DeepSeek / Qwen / 智谱 / 火山方舟など）を入力してください：

```properties
# config.properties
base-url=https://api.deepseek.com/v1
api-key=sk-xxx
model=deepseek-chat
```

> キー未設定の場合は内蔵の **Mock モデル**が自動で使われます：ネット接続不要・課金なしで、ReAct ループやツール呼び出し、ルーティング判断、RAG 検索などの実行トレースをすべて表示できます。キーを設定すれば、同じ骨格がそのまま本物の脳を手に入れます。Mock スクリプトは 5 言語でシナリオ単位に一致しています。

## プロジェクト構成

```
ToyAgent/
├── pom.xml                              # cn.xiaofuge.ai:ToyAgent:1.0（依存ゼロ）
├── config.properties.example            # モデル設定テンプレート
├── docs/images/                         # スクリーンショット
├── web/                                 # プレイグラウンド（素の HTML/CSS/JS）
│   ├── index.html
│   ├── css/style.css                    # デザイントークン + 7 テーマ + 3 カラム
│   └── js/
│       ├── data.js                      # シナリオメタデータ + サンプル質問
│       ├── articles.js                  # 解説記事 27 本
│       ├── code-*.js                    # 記事コードブロックの 4 言語フラグメント
│       └── app.js                       # ナビ/チャット/トレース/5 言語コード切替
└── src/main/java/cn/xiaofuge/ai/
    ├── Application.java                 # HTTP サーバ：静的ページ + API ルーティング
    ├── llm/                             # モデル層
    │   ├── ChatModel.java               # モデル抽象（脳）
    │   ├── OpenAiChatModel.java         # OpenAI 互換クライアント
    │   ├── MockChatModel.java           # 教学 Mock モデル（26 スクリプト）
    │   ├── Models.java                  # 設定ファクトリ
    │   ├── Message.java                 # チャットメッセージ record
    │   └── Json.java                    # 依存ゼロの JSON コーデック
    └── agent/
        ├── Agent.java                   # エージェント契約：chat(input)
        └── step01..step26/              # 26 シナリオ、1 シナリオ 1 クラス
```

## HTTP API

| メソッド | パス | 説明 |
|------|------|------|
| GET  | `/` | プレイグラウンド |
| GET  | `/api/config` | 現在のモデルモード（mock / real） |
| POST | `/api/{stepId}/chat` | 対話、ボディ `{"message": "..."}` |
| POST | `/api/{stepId}/reset` | シナリオ状態のリセット（記憶など） |

## 設計思想

1. **1 インターフェースクラス = 1 エージェント MVP** —— フレームワークの魔法に頼らず、ロジックはすべて目で見て手で触れます；
2. **依存関係ゼロ** —— JDK 標準機能のみ（`com.sun.net.httpserver` + `java.net.http`）、JSON は自前実装、clone すればすぐ動く；
3. **段階的な学習** —— 26 シナリオを 7 つのテーマアークで厳密に進め、各シナリオで能力をひとつだけ追加。サイドバーのグループ分けが学習マップ、インターフェースコメントがチュートリアル；
4. **ページがそのままテスト** —— 素の HTML/CSS/JS の 3 カラム構成：シナリオナビ、チャット + トレースタイムライン + mermaid 図、5 言語コード切替付き解説記事；
5. **Mock / 実モデルの二モード** —— 骨格と脳を分離。学習中は Mock でトレースを観察し、進んだらキーを設定して実モデルへ；
6. **多言語等価実装** —— 同じ教学ストーリーを 5 言語でシナリオ単位に揃え、好きな言語でエージェントの本質を理解できます。

## License

Apache-2.0 © 小傅哥（Xiaofuge）
