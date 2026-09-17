export interface TagDTO {
  id: number
  name: string
}

export interface TagCreateDTO {
  name: string
}

export interface ComicTagUpdateDTO {
  tagIds: number[]
}
