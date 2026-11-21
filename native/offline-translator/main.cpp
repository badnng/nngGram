#include <algorithm>
#include <iostream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "ctranslate2/translator.h"
#include "fasttext.h"
#include "sentencepiece_processor.h"

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << "nnggram-translator 1\n";
        return 0;
    }
#ifdef _WIN32
    if (_setmode(_fileno(stdin), _O_BINARY) == -1 ||
        _setmode(_fileno(stdout), _O_BINARY) == -1) return 1;
#endif
    try {
        if (argc == 2 && std::string(argv[1]) == "--detect") {
            std::string text(std::istreambuf_iterator<char>(std::cin), {});
            if (text.size() > 16384) return 1;
            std::replace(text.begin(), text.end(), '\n', ' ');
            std::istringstream input(text + '\n');
            fasttext::FastText detector;
            detector.loadModel("lid.176.ftz");
            std::vector<std::pair<fasttext::real, std::string>> guesses;
            detector.predictLine(input, guesses, 1, 0.40f);
            if (!guesses.empty() && guesses.front().second == "__label__en") std::cout << "en";
            return std::cout.good() ? 0 : 1;
        }
        sentencepiece::SentencePieceProcessor source, target;
        if (!source.Load("source.spm").ok() || !target.Load("target.spm").ok()) return 1;
        ctranslate2::Translator translator(".", ctranslate2::Device::CPU);
        ctranslate2::TranslationOptions options;
        options.beam_size = 4;
        options.no_repeat_ngram_size = 2;
        options.max_input_length = 130;
        options.max_decoding_length = 192;

        std::string line, output;
        bool first = true;
        while (std::getline(std::cin, line)) {
            if (line.size() > 16384) return 1;
            if (!first) output.push_back('\n');
            first = false;
            std::vector<std::string> tokens;
            if (!source.Encode(line, &tokens).ok()) return 1;
            std::vector<std::vector<std::string>> batch;
            for (size_t start = 0; start < tokens.size(); start += 128) {
                std::vector<std::string> chunk{">>cmn_Hans<<"};
                chunk.insert(chunk.end(), tokens.begin() + start,
                             tokens.begin() + std::min(start + 128, tokens.size()));
                // The converted Marian model expects its tokenizer to append EOS.
                chunk.emplace_back("</s>");
                batch.push_back(std::move(chunk));
            }
            for (const auto& result : translator.translate_batch(batch, options, 4)) {
                std::string translated;
                if (!target.Decode(result.output(), &translated).ok()) return 1;
                output += translated;
            }
            if (output.size() > 128 * 1024) return 1;
        }
        std::cout << output;
        return std::cout.good() ? 0 : 1;
    } catch (const std::exception&) {
        return 1;
    }
}
