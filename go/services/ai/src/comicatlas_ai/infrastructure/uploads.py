"""通过公开 multipart 回调逐块接收；不加载整包或信任上传文件名。"""

import tempfile

from python_multipart import MultipartParser
from python_multipart.exceptions import MultipartParseError
from python_multipart.multipart import parse_options_header

from ..domain import rules
from ..domain.errors import InvalidInput
from ..domain.models import MAX_BODY_BYTES, MAX_MANIFEST_BYTES, MAX_SAMPLES


class BodyTooLarge(InvalidInput):
    pass


class Upload:
    def __init__(self, content_type: str):
        kind, options = parse_options_header(content_type)
        boundary = options.get(b"boundary", b"")
        if (kind != b"multipart/form-data" or not 1 <= len(boundary) <= 70 or
                any(char < 33 or char > 126 for char in boundary)):
            raise InvalidInput()
        self.analysis = None
        self.files = []
        self.total = 0
        self.parts = 0
        self.ended = False
        self.parser = MultipartParser(boundary, {
            "on_part_begin": self._begin, "on_header_field": self._header_field,
            "on_header_value": self._header_value, "on_header_end": self._header_end,
            "on_headers_finished": self._headers_done,
            "on_part_data": self._data, "on_part_end": self._part_end,
            "on_end": self._end}, max_size=MAX_BODY_BYTES,
            max_header_count=2, max_header_size=4096)

    def _begin(self):
        self.parts += 1
        if self.parts > MAX_SAMPLES + 1:
            raise InvalidInput()
        self.headers = {}
        self.header_name = bytearray()
        self.header_value = bytearray()
        self.manifest = bytearray()
        self.current = None
        self.size = 0

    def _header_field(self, data, start, end):
        self.header_name.extend(data[start:end])

    def _header_value(self, data, start, end):
        self.header_value.extend(data[start:end])

    def _header_end(self):
        name = bytes(self.header_name).lower()
        if name not in (b"content-disposition", b"content-type") or name in self.headers:
            raise InvalidInput()
        self.headers[name] = bytes(self.header_value)
        self.header_name.clear()
        self.header_value.clear()

    def _headers_done(self):
        if set(self.headers) != {b"content-disposition", b"content-type"}:
            raise InvalidInput()
        kind, options = parse_options_header(self.headers[b"content-disposition"])
        if kind != b"form-data":
            raise InvalidInput()
        if self.parts == 1:
            if (options.get(b"name") != b"manifest" or b"filename" in options or
                    self.headers[b"content-type"] != b"application/json"):
                raise InvalidInput()
            return
        index = self.parts - 2
        if self.analysis is None or index >= len(self.analysis.samples):
            raise InvalidInput()
        sample = self.analysis.samples[index]
        if (options.get(b"name") != sample.field.encode("ascii") or
                not options.get(b"filename") or
                self.headers[b"content-type"] != sample.content_type.encode("ascii")):
            raise InvalidInput()
        self.current = tempfile.TemporaryFile(mode="w+b")
        self.files.append(self.current)

    def _data(self, data, start, end):
        count = end - start
        self.size += count
        if self.parts == 1:
            if self.size > MAX_MANIFEST_BYTES:
                raise BodyTooLarge()
            self.manifest.extend(data[start:end])
        else:
            if self.size > self.analysis.samples[self.parts - 2].size:
                raise InvalidInput()
            self.current.write(data[start:end])

    def _part_end(self):
        if self.parts == 1:
            self.analysis = rules.manifest(bytes(self.manifest))
            self.manifest.clear()
        else:
            if self.size != self.analysis.samples[self.parts - 2].size:
                raise InvalidInput()
            self.current.seek(0)

    def _end(self):
        self.ended = True

    def write(self, data: bytes):
        self.total += len(data)
        if self.total > MAX_BODY_BYTES:
            raise BodyTooLarge()
        try:
            self.parser.write(data)
        except MultipartParseError as error:
            raise InvalidInput() from error

    def finish(self):
        self.parser.finalize()
        if (not self.ended or self.analysis is None or
                len(self.files) != len(self.analysis.samples)):
            raise InvalidInput()

    def close(self):
        for source in self.files:
            source.close()
