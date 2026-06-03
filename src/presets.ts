import { PresetBook } from "./types";

export const PRESET_BOOKS: PresetBook[] = [
  {
    title: "Енеїда",
    author: "Іван Котляревський",
    year: 1798,
    isbn: "978-966-03-8120-9",
    genre: "Classic Literature",
    fileType: "pdf",
    ocrTextSample: `IВАН КOTЛЯPEBCЬКИЙ
ЕНЕЇДА
Поема в шести частинах
Харків
Мiнiстерство освіти України, 1993 р.
Бiблiотека укр. клясики.
УДК 821.161.2
ББК 84(4УКР)
ISBN 978-966-03-8120-9 (Помилка друку: 1SВN 978-966-О3-8120-9)
Еней був парубок моторний i хлопець хоть куди козак...
Зшито у друкарні Г. П. Квітки, 1798 рік вид.`,
  },
  {
    title: "Кайдашева сім'я",
    author: "Іван Нечуй-Левицький",
    year: 1879,
    isbn: "", // No ISBN, forcing Gemini OCR correction!
    genre: "Fiction",
    fileType: "djvu",
    ocrTextSample: `КИЇB - ДЕPЖABA BИД-BO «ДHІПPO» - 1983
ІВАН НЕЧУЙ-ЛЕВИЦЬКИЙ
КАЙДAШEBA СІМ'Я
Повiсть на дві часті.
Мал. О. Данченка.
[ OCR ERROR: Heчуй-Jeвицbкuй: Kaйдашеbа сiм'я. Повістb, напuсана у 1878-1879 роках. ]
Карпо i Лаврін стояли під повіткою і стругали...`,
  },
  {
    title: "Кобзар",
    author: "Тарас Шевченко",
    year: 1840,
    isbn: "978-966-2449-01-3",
    genre: "Poetry",
    fileType: "pdf",
    ocrTextSample: `Тарас ШЕВЧЕНКО
К О Б З А Р
Повне видання з ілюстраціями.
Видавництво Книжковий Клуб, 2012
Оригінальне видання датується 1840 роком у Санкт-Петербурзі.
ІSВN 978 - 966 - 2449 - 01 - 3
[ OCR Noise: Т. Г. Шeвчeнкo, Ko6зapь. Думи мої, думи мої, лихо мені з вами! ]`,
  },
  {
    title: "Structure and Interpretation of Computer Programs",
    author: "Harold Abelson, Gerald Jay Sussman",
    year: 1996,
    isbn: "0-262-51087-1",
    genre: "Computer Science",
    fileType: "epub",
    ocrTextSample: `Structure and Interpretation of Computer Programs
Second Edition
Harold Abelson and Gerald Jay Sussman
with Julie Sussman
The MIT Press, Cambridge, Massachusetts
ISBN 0-262-51087-1 (paperback), 0-262-01153-0 (hardcover)
Copyright 1996 by The Massachusetts Institute of Technology.`,
  }
];
