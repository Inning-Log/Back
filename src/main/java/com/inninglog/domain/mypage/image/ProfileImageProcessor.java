package com.inninglog.domain.mypage.image;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProfileImageProcessor {
    public byte[] sanitize(byte[] bytes, String contentType) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!(contentType.equals("image/png") && format.equalsIgnoreCase("png"))
                        && !(contentType.equals("image/jpeg") && format.equalsIgnoreCase("jpeg"))) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_000_000)
                    throw new ProfileImageException(HttpStatus.BAD_REQUEST, "PROFILE_IMAGE_DIMENSIONS_EXCEEDED",
                            "이미지는 가로·세로 8192px, 총 1600만 픽셀 이하여야 합니다.");
                var parameters = reader.getDefaultReadParam();
                int subsampling = Math.max(1, Math.max(width, height) / 1024);
                parameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                var source = reader.read(0, parameters);
                double scale = Math.min(1.0, 1024.0 / Math.max(width, height));
                var result = new BufferedImage(Math.max(1, (int) (width * scale)),
                        Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_ARGB);
                var graphics = result.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, result.getWidth(), result.getHeight(), null);
                } finally { graphics.dispose(); }
                var output = new ByteArrayOutputStream();
                ImageIO.write(result, "png", output);
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException e) { throw invalid(); }
    }

    private static ProfileImageException invalid() {
        return new ProfileImageException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE_IMAGE",
                "정상적인 JPEG 또는 PNG 이미지가 아닙니다.");
    }
}
