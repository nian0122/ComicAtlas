/* djpeg 每个进程处理一张图片；工作目录由调用方设为独占的媒体暂存目录。 */
#define _GNU_SOURCE
#define JPEG_INTERNALS
#include "jinclude.h"
#include "jpeglib.h"
#include "jmemsys.h"
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

static long reserved_bytes;
static const long scratch_limit = 1024L * 1024 * 1024;

static void read_coefficients(j_common_ptr cinfo, backing_store_ptr store,
                              void *buffer, long offset, long count)
{
  if (fseek(store->temp_file, offset, SEEK_SET) != 0 ||
      fread(buffer, 1, count, store->temp_file) != (size_t)count)
    ERREXIT(cinfo, JERR_TFILE_READ);
}

static void write_coefficients(j_common_ptr cinfo, backing_store_ptr store,
                               void *buffer, long offset, long count)
{
  if (fseek(store->temp_file, offset, SEEK_SET) != 0 ||
      fwrite(buffer, 1, count, store->temp_file) != (size_t)count)
    ERREXIT(cinfo, JERR_TFILE_WRITE);
}

static void close_coefficients(j_common_ptr cinfo, backing_store_ptr store)
{
  fclose(store->temp_file);
  unlink(store->temp_name);
}

/* Windows 挂载卷可能缺少 fallocate；按大块写满，避免 libc 逐块探测的往返。 */
static int reserve_space(int fd, long bytes)
{
  static const char zeros[1024 * 1024];
  if (fallocate(fd, 0, 0, bytes) == 0)
    return 0;
  if (errno != EOPNOTSUPP && errno != ENOSYS && errno != EINVAL)
    return -1;
  while (bytes > 0) {
    size_t count = bytes < (long)sizeof(zeros) ? (size_t)bytes : sizeof(zeros);
    ssize_t written = write(fd, zeros, count);
    if (written < 0 && errno == EINTR)
      continue;
    if (written <= 0)
      return -1;
    bytes -= written;
  }
  return 0;
}

void __wrap_jpeg_open_backing_store(j_common_ptr cinfo, backing_store_ptr store,
                                    long bytes)
{
  if (bytes <= 0 || bytes > scratch_limit - reserved_bytes)
    ERREXIT(cinfo, JERR_NO_BACKING_STORE);

  strcpy(store->temp_name, "jpeg-coeff-XXXXXX");
  int fd = mkstemp(store->temp_name);
  if (fd < 0)
    ERREXITS(cinfo, JERR_TFILE_CREATE, store->temp_name);

  /* 在开始写入系数前预留实际空间，避免稀疏文件在解码中途耗尽磁盘。 */
  if (reserve_space(fd, bytes) != 0 ||
      (store->temp_file = fdopen(fd, "w+b")) == NULL) {
    close(fd);
    unlink(store->temp_name);
    ERREXIT(cinfo, JERR_TFILE_WRITE);
  }
  reserved_bytes += bytes;
  store->read_backing_store = read_coefficients;
  store->write_backing_store = write_coefficients;
  store->close_backing_store = close_coefficients;
}
