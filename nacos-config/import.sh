#!/bin/bash
# ============================================================
# Nacos 配置导入脚本
# 将 nacos-config/ 目录下的配置文件导入到 Nacos 配置中心
#
# 用法:
#   ./nacos-config/import.sh <nacos-addr> [namespace]
#
# 示例:
#   ./nacos-config/import.sh localhost:8848 dev
#   ./nacos-config/import.sh 10.88.8.11:30002 prod
# ============================================================

set -euo pipefail

NACOS_ADDR="${1:?用法: $0 <nacos-addr> [namespace]}"
NAMESPACE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GROUP="DEFAULT_GROUP"

echo "============================================"
echo " Nacos 配置导入"
echo " 地址: http://${NACOS_ADDR}/nacos"
echo " 命名空间: ${NAMESPACE:-默认(public)}"
echo " 分组: ${GROUP}"
echo "============================================"
echo ""

import_config() {
    local file="$1"
    local data_id
    data_id=$(basename "$file")

    echo -n "  导入 ${data_id} ... "

    local namespace_param=""
    if [ -n "$NAMESPACE" ]; then
        namespace_param="&namespaceId=${NAMESPACE}"
    fi

    local content
    content=$(cat "$file")

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "http://${NACOS_ADDR}/nacos/v1/cs/configs" \
        -d "dataId=${data_id}&group=${GROUP}&type=yaml${namespace_param}" \
        --data-urlencode "content=${content}")

    if [ "$http_code" = "200" ]; then
        echo "✅ 成功"
    else
        echo "❌ 失败 (HTTP ${http_code})"
    fi
}

echo "开始导入配置文件..."
echo ""

for f in "${SCRIPT_DIR}"/*.yml; do
    [ -f "$f" ] || continue
    import_config "$f"
done

echo ""
echo "============================================"
echo " 导入完成！"
echo " 请登录 Nacos 控制台验证配置: http://${NACOS_ADDR}/nacos"
echo "============================================"
