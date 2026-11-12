"""与 Go Unicode 17.0 简单小写映射一致，不使用上下文或多字符展开。"""

# Python 3.12 的 Unicode 15 表之外新增的简单映射，以及完整小写的展开例外。
_ADDITIONS = {0x130: 0x69, 0x1C89: 0x1C8A, 0xA7CB: 0x264,
              0xA7CC: 0xA7CD, 0xA7CE: 0xA7CF, 0xA7D2: 0xA7D3,
              0xA7D4: 0xA7D5, 0xA7DA: 0xA7DB, 0xA7DC: 0x19B}


def lower(value: str) -> str:
    result = []
    for char in value:
        number = ord(char)
        if number in _ADDITIONS:
            result.append(chr(_ADDITIONS[number]))
        elif 0x10D50 <= number <= 0x10D65:
            result.append(chr(number + 32))
        elif 0x16EA0 <= number <= 0x16EB8:
            result.append(chr(number + 27))
        else:
            result.append(char.lower())
    return "".join(result)
