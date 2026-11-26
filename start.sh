#!/usr/bin/env sh
# ComicAtlas 一键准备并启动（Docker Compose）。
set -eu

if ! SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P); then
    printf '错误：无法定位脚本目录\n' >&2
    exit 1
fi
ENV_FILE="$SCRIPT_DIR/.env"
EXAMPLE_FILE="$SCRIPT_DIR/.env.example"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"

usage() {
    cat <<'EOF'
用法：sh start.sh [选项]
  --no-build        不重新构建镜像，直接启动
  --prepare-only    只生成 .env 和存储目录，并校验 Compose 配置
  --storage-root P  指定存储目录（默认 <项目>/storage）
  -h, --help        显示帮助
EOF
}

fail() {
    printf '错误：%s\n' "$1" >&2
    exit 1
}

NO_BUILD=0
PREPARE_ONLY=0
STORAGE_ROOT=''
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) NO_BUILD=1 ;;
        --prepare-only) PREPARE_ONLY=1 ;;
        --storage-root)
            shift
            [ $# -gt 0 ] || fail '--storage-root 缺少路径'
            STORAGE_ROOT=$1
            ;;
        --storage-root=*) STORAGE_ROOT=${1#*=} ;;
        -h|--help) usage; exit 0 ;;
        *) fail "未知参数：$1" ;;
    esac
    shift
done

[ -f "$COMPOSE_FILE" ] || fail '缺少 compose.yaml'
[ -f "$EXAMPLE_FILE" ] || fail '缺少 .env.example'

read_env() {
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" |
        tail -n 1 |
        sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//" |
        tr -d '\r'
}

resolve_storage() {
    value=$1
    case "$value" in
        "~") value=$HOME ;;
        "~/"*) value="$HOME/${value#~/}" ;;
    esac
    case "$value" in
        /*) ;;
        *) value="$SCRIPT_DIR/$value" ;;
    esac
    case "$value" in
        /) ;;
        */) value=${value%/} ;;
    esac
    printf '%s' "$value"
}

if [ -n "$STORAGE_ROOT" ]; then
    STORAGE_ROOT=$(resolve_storage "$STORAGE_ROOT")
fi

write_env_root() {
    source=$1
    tmp="$ENV_FILE.tmp.$$"
    if ! STORAGE_ROOT="$STORAGE_ROOT" awk '
        BEGIN { root = ENVIRON["STORAGE_ROOT"]; done = 0 }
        /^[[:space:]]*COMICATLAS_STORAGE_ROOT[[:space:]]*=/ {
            print "COMICATLAS_STORAGE_ROOT=" root
            done = 1
            next
        }
        { print }
        END { if (!done) print "COMICATLAS_STORAGE_ROOT=" root }
    ' "$source" > "$tmp"; then
        rm -f "$tmp"
        fail '写入 .env 失败。'
    fi
    if ! mv "$tmp" "$ENV_FILE"; then
        rm -f "$tmp"
        fail '写入 .env 失败。'
    fi
    chmod 600 "$ENV_FILE" || fail '无法设置 .env 权限。'
}

ENV_CREATED=0
ENV_NEEDS_ROOT=0
if [ -f "$ENV_FILE" ]; then
    EXISTING=$(read_env COMICATLAS_STORAGE_ROOT)
    if [ -z "$EXISTING" ]; then
        [ -n "$STORAGE_ROOT" ] || fail '已有 .env 缺少 COMICATLAS_STORAGE_ROOT；请补齐或删除 .env 让脚本生成。'
        EXISTING=$STORAGE_ROOT
        ENV_NEEDS_ROOT=1
    fi
    if [ -n "$STORAGE_ROOT" ]; then
        EXISTING_RESOLVED=$(resolve_storage "$EXISTING")
        [ "$STORAGE_ROOT" = "$EXISTING_RESOLVED" ] ||
            fail '已有 .env 的 COMICATLAS_STORAGE_ROOT 与 --storage-root 不一致；请直接修改 .env 或删除后重试。'
    fi
    STORAGE_ROOT=$(resolve_storage "$EXISTING")
else
    [ -n "$STORAGE_ROOT" ] || STORAGE_ROOT=$(resolve_storage 'storage')
    ENV_CREATED=1
fi

mkdir -p "$STORAGE_ROOT/imports" "$STORAGE_ROOT/media" ||
    fail "无法创建存储目录：$STORAGE_ROOT"
if ! STORAGE_ROOT=$(CDPATH= cd -- "$STORAGE_ROOT" && pwd -P); then
    fail '无法解析存储目录。'
fi

if [ "$ENV_CREATED" -eq 1 ]; then
    write_env_root "$EXAMPLE_FILE"
    printf '已创建 .env，存储目录：%s\n' "$STORAGE_ROOT"
elif [ "$ENV_NEEDS_ROOT" -eq 1 ] || [ "$EXISTING" != "$STORAGE_ROOT" ]; then
    write_env_root "$ENV_FILE"
    printf '已把 .env 中的存储路径规范化为：%s\n' "$STORAGE_ROOT"
fi

command -v docker >/dev/null 2>&1 || fail '未找到 docker，请先安装 Docker Desktop 或 Docker Engine。'
docker compose version >/dev/null 2>&1 || fail '未找到 docker compose 插件；请安装 Docker Compose v2。'

compose() {
    docker compose --project-directory "$SCRIPT_DIR" --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

if [ "$PREPARE_ONLY" -eq 1 ]; then
    compose --profile ai config --quiet || fail 'Compose 配置校验失败。'
    printf '准备完成：%s；存储目录：%s\n' "$ENV_FILE" "$STORAGE_ROOT"
    exit 0
fi

docker info >/dev/null 2>&1 || fail '无法连接 Docker daemon；请先启动 Docker Desktop。'
if ! compose up --help 2>&1 | grep -q -- '--wait'; then
    fail '当前 docker compose 不支持 --wait；请升级到 Compose v2.17 或更高版本。'
fi

if [ "$NO_BUILD" -eq 1 ]; then
    if ! images=$(compose config --images); then
        fail '无法读取 Compose 镜像列表。'
    fi
    for image in $images; do
        [ -n "$image" ] || continue
        docker image inspect "$image" >/dev/null 2>&1 ||
            fail "缺少镜像 $image；请去掉 --no-build 执行一次完整构建。"
    done
fi

if [ "$(uname -s)" = 'Linux' ]; then
    needs_chown=0
    for directory in "$STORAGE_ROOT" "$STORAGE_ROOT/imports" "$STORAGE_ROOT/media"; do
        owner=$(stat -c '%u' "$directory" 2>/dev/null || true)
        [ "$owner" = '10001' ] || needs_chown=1
    done
    if [ "$needs_chown" -eq 1 ]; then
        printf '正在设置存储目录属主（10001:10001）...\n'
        if ! docker run --rm --pull missing --user 0:0 -v "$STORAGE_ROOT:/volume" alpine:3.20 \
            chown -R 10001:10001 /volume >/dev/null; then
            fail "无法设置存储目录属主；请执行 sudo chown -R 10001:10001 \"$STORAGE_ROOT\" 后重试。"
        fi
    fi
fi

start_compose() {
    if [ "$NO_BUILD" -eq 1 ]; then
        compose up -d --wait --wait-timeout 180 --no-build
    else
        compose up -d --wait --wait-timeout 180 --build
    fi
}

if [ "$NO_BUILD" -eq 1 ]; then
    printf '启动 ComicAtlas...\n'
else
    printf '构建并启动 ComicAtlas（首次构建需要网络）...\n'
fi
if ! start_compose; then
    printf '启动失败。查看日志：\n' >&2
    printf '  docker compose --project-directory "%s" --env-file "%s" -f "%s" logs --tail=100\n' \
        "$SCRIPT_DIR" "$ENV_FILE" "$COMPOSE_FILE" >&2
    exit 1
fi

WEB_PORT=$(read_env COMICATLAS_WEB_PORT)
[ -n "$WEB_PORT" ] || WEB_PORT=18078
ADMIN_PORT=$(read_env COMICATLAS_ADMIN_PORT)
[ -n "$ADMIN_PORT" ] || ADMIN_PORT=18079

printf 'ComicAtlas 已启动。\n'
printf '阅读：http://127.0.0.1:%s/\n' "$WEB_PORT"
printf '管理：http://127.0.0.1:%s/manage\n' "$ADMIN_PORT"
printf '导入：把文件或目录放入 %s/imports，管理后台导入时填写该目录内的完整路径。\n' "$STORAGE_ROOT"
