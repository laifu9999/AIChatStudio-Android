#!/usr/bin/env bash
# 乐乐修图 · 云端一键训练脚本（在 AutoDL/GPU 实例上运行）
# 用法: bash run_cloud.sh            （默认 1500 步）
#       STEPS=3000 bash run_cloud.sh （自定义步数）
set -e
echo "===== 乐乐云端训练开始 $(date +%F\ %T) ====="

# SSH 非交互 shell 没有 conda PATH：硬编码补上（常见位置探测）
export PATH="/root/miniconda3/bin:/opt/conda/bin:$PATH"
command -v python >/dev/null || export PATH="$(dirname "$(find /root /opt -maxdepth 3 -name python -type f 2>/dev/null | head -1)"):$PATH"

# AutoDL/仙宫云镜像一般自带 torch；缺啥装啥
python -c "import torch, cv2, numpy" 2>/dev/null || \
    python -m pip install -q torch numpy opencv-python-headless
python -c "import onnx" 2>/dev/null || python -m pip install -q onnx

python - <<'EOF'
import torch
print("设备:", "CUDA ✓", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU(没租到卡!)")
EOF

# 热启动：仓库里有 init_model.pt 就从它继续练（累积训练，越练越强）
INIT=""
[ -f init_model.pt ] && INIT="--init init_model.pt" && echo "热启动: init_model.pt"

# 步数默认拉满（8000 步 ≈ 4090 上 10 分钟）；要更狠可 STEPS=12000 bash run_cloud.sh
python train_cloud.py --data-dir . --steps "${STEPS:-8000}" --batch "${BATCH:-16}" $INIT

# 结果自动回传 GitHub；并把新权重存为种子，下次训练自动从它继续（越练越强）
cp output/spot_fix_unet.pt init_model.pt

# 结果自动回传 GitHub
git config user.email "laifu9999@users.noreply.github.com"
git config user.name "laifu9999"
git add output/ init_model.pt 2>/dev/null || true
git commit -m "train result $(date +%F-%H%M%S)" || echo "(无新结果可提交)"
git push origin HEAD
echo "===== 训练完成，结果已自动回传 GitHub，可以关机了 ====="
