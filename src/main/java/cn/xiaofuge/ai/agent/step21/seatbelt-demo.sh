#!/bin/bash
# ============================================================
# macOS Seatbelt 真沙箱演示 —— Claude Code 同款机制
# 运行：bash seatbelt-demo.sh（需在用户自己的 Terminal 里跑，
#       部分托管环境进程树处于受限上下文，无法套沙箱）
# ============================================================
set -u

TOY_DIR="$(cd "$(dirname "$0")" && git rev-parse --show-toplevel 2>/dev/null || echo "$(cd "$(dirname "$0")/../../../../../.." && pwd)")"
WS="$TOY_DIR/sandbox"
PROFILE="$(mktemp /tmp/toyagent-sandbox.XXXX.sb)"

cat > "$PROFILE" <<EOF
(version 1)
(allow default)
(deny file-write*)
(allow file-write* (subpath "$WS"))
(deny network*)
(deny file-read* (subpath "$HOME/.ssh") (subpath "$HOME/.aws"))
EOF

echo "沙箱工作区: $WS"
echo "Profile: $PROFILE"
echo

echo "=== 测试 1 · 写工作区（应成功，内核放行） ==="
sandbox-exec -f "$PROFILE" /bin/sh -c "echo sandboxed-ok > '$WS/probe.txt' && cat '$WS/probe.txt'"
echo "exit=$?"; echo

echo "=== 测试 2 · 写工作区外 /tmp（应被内核拒绝） ==="
sandbox-exec -f "$PROFILE" /bin/sh -c "echo evil > /tmp/evil-by-sbx.txt"
echo "exit=$?"; echo

echo "=== 测试 3 · 网络（应被拒绝：allowlist 默认为空） ==="
sandbox-exec -f "$PROFILE" /usr/bin/curl -s -m 3 https://example.com
echo "exit=$?"; echo

echo "=== 测试 4 · 读凭据目录 ~/.ssh（应被拒绝：denyRead） ==="
sandbox-exec -f "$PROFILE" /bin/sh -c "ls '$HOME/.ssh'"
echo "exit=$?"; echo

echo "=== 测试 5 · 编码绕过（教学核心：字符串层看不出来的恶意命令） ==="
echo "  发送的字面命令: echo \$B64 | base64 -d | sh   （B64 是 touch /tmp/evil2.txt 的编码）"
B64=$(printf 'touch /tmp/evil2.txt' | base64)
sandbox-exec -f "$PROFILE" /bin/sh -c "echo $B64 | base64 -d | sh"
echo "exit=$?  —— 应用层四层检查对这条命令束手无策，内核却直接拒绝"
echo

rm -f "$WS/probe.txt" "$PROFILE"
echo "完成。对比结论：审查式检查拦『看得出的坏』，内核边界拦『做得了的坏』。"
