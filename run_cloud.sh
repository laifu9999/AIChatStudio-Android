#!/usr/bin/env bash
# 乐乐修图 · 云端一键训练脚本（在 AutoDL/GPU 实例上运行）
# 用法: bash run_cloud.sh            （默认 1500 步）
#       STEPS=3000 bash run_cloud.sh （自定义步数）
set -e
echo "===== 乐乐云端训练开始 $(date +%F\ %T) ====="

# AutoDL 镜像一般自带 torch；缺啥装啥
python -c "import torch, cv2, numpy" 2>/dev/null || \
    pip install -q torch numpy opencv-python-headless

python - <<'EOF'
import torch
print("设备:", "CUDA ✓", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU(没租到卡!)")
EOF

python train_cloud.py --data-dir . --steps "${STEPS:-1500}" --batch "${BATCH:-16}"

# 结果自动回传 GitHub
git config user.email "laifu9999@users.noreply.github.com"
git config user.name "laifu9999"
git add output/ 2>/dev/null || true
git commit -m "train result $(date +%F-%H%M%S)" || echo "(无新结果可提交)"
git push origin HEAD
echo "===== 训练完成，结果已自动回传 GitHub，可以关机了 ====="
