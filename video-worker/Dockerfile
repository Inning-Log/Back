# AWS Lambda container image for game video jobs (x86_64).
FROM public.ecr.aws/lambda/python:3.12

# Static FFmpeg build (includes libx264 and AAC). Pin the version you tested with.
RUN dnf install -y tar xz && dnf clean all \
 && curl -fsSL https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz -o /tmp/ffmpeg.tar.xz \
 && tar -xJf /tmp/ffmpeg.tar.xz -C /tmp \
 && cp /tmp/ffmpeg-*-amd64-static/ffmpeg /tmp/ffmpeg-*-amd64-static/ffprobe /usr/local/bin/ \
 && rm -rf /tmp/ffmpeg*

COPY requirements.txt ${LAMBDA_TASK_ROOT}/
RUN pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/requirements.txt

# Fonts and team logos used when drawing. Logos: assets/teams/<team_code>.png (LG, HH, SK, SS, NC, KT, LT, HT, OB, WO)
COPY assets/fonts/ /opt/assets/fonts/
COPY assets/teams/ /opt/assets/teams/
ENV FONT=/opt/assets/fonts/regular.ttf \
    FONT_BOLD=/opt/assets/fonts/bold.ttf \
    TEAM_LOGO_DIR=/opt/assets/teams

COPY handler.py render_segment.py ${LAMBDA_TASK_ROOT}/
CMD ["handler.handler"]
