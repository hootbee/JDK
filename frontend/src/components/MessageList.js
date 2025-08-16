import React from "react";
import styled, { keyframes } from "styled-components";
import UtilizationDashboard from "./UtilizationDashboard";
import ReactMarkdown from 'react-markdown';

const SearchResults = ({ data }) => (
    <SearchResultsContainer>
        <h4><span role="img" aria-label="icon">🔍</span> 검색 결과 ({data.totalCount}개)</h4>
        <ResultsList>
            {data.results.map((result, index) => (
                <ResultItem key={index}>{result}</ResultItem>
            ))}
        </ResultsList>
        <TipMessage>
            💡 특정 데이터에 대한 자세한 정보가 필요하시면 '[파일명] 상세정보' 또는 '[파일명] 자세히'라고 말씀하세요.
        </TipMessage>
    </SearchResultsContainer>
);

const SearchNotFound = ({ data }) => (
    <SearchNotFoundContainer>
        <h4><span role="img" aria-label="icon">😕</span> 데이터를 찾을 수 없습니다.</h4>
        <p>다음 검색어를 확인해보세요: <strong>{data.failedKeywords.join(', ')}</strong></p>
        {data.regionKeyword && <p>해당 지역(<strong>{data.regionKeyword}</strong>)의 데이터가 부족할 수 있습니다.</p>}
        <p>다른 지역의 유사한 데이터를 찾아보거나, 더 일반적인 검색어로 다시 시도해보세요.</p>
    </SearchNotFoundContainer>
);

const DataDetailView = ({ data }) => {
    if (!data) return null;
    return (
        <DetailContainer>
            <h3><span role="img" aria-label="icon">📋</span> {data.title || '데이터 상세 정보'}</h3>
            <DetailGrid>
                <DetailItem><strong>📄 파일명:</strong> {data.fileDataName}</DetailItem>
                <DetailItem><strong>📅 수정일:</strong> {data.modifiedDate}</DetailItem>
                <DetailItem><strong>📂 분류:</strong> {data.classificationSystem}</DetailItem>
                <DetailItem><strong>🏢 제공기관:</strong> {data.providerAgency}</DetailItem>
            </DetailGrid>
            {data.keywords && data.keywords.length > 0 && (
                <KeywordSection>
                    <strong>🔑 키워드:</strong>
                    <KeywordContainer>
                        {data.keywords.map((kw, i) => <KeywordTag key={i}>{kw}</KeywordTag>)}
                    </KeywordContainer>
                </KeywordSection>
            )}
            {data.description && (
                 <DescriptionSection>
                    <strong>📝 상세 설명:</strong>
                    <blockquote>{data.description}</blockquote>
                </DescriptionSection>
            )}
        </DetailContainer>
    );
};

const HelpMessage = () => (
    <HelpContainer>
        <h4><span role="img" aria-label="icon">👋</span> 안녕하세요! ODA(Open Data Assistant)입니다.</h4>
        <p>저는 공공 데이터를 찾고 활용하는 것을 돕는 AI 챗봇입니다. 다음과 같이 질문해보세요:</p>
        <HelpList>
            <li><strong>특정 데이터 검색:</strong> '서울시 교통 데이터 보여줘'</li>
            <li><strong>데이터 상세 정보:</strong> '[파일명] 자세히' 또는 '[파일명] 상세정보'</li>
            <li><strong>데이터 활용 방안:</strong> '[파일명] 전체 활용' 또는 '[파일명] 비즈니스 활용'</li>
            <li><strong>새로운 데이터 검색 시작:</strong> '다른 데이터 조회'</li>
        </HelpList>
    </HelpContainer>
);

function MessageList({ messages, onCategorySelect, isTyping, scrollContainerRef, messageEndRef, onScroll }) {
  return (
    <MessageListContainer ref={scrollContainerRef} onScroll={onScroll}>
      {messages.map((message) => (
        <MessageItem key={message.id} sender={message.sender} type={message.type}>
          {message.type === "search_results" ? (
            <SearchResults data={message.data} />
          ) : message.type === "search_not_found" ? (
            <SearchNotFound data={message.data} />
          ) : message.type === "context_reset" ? (
            <ContextResetMessage>
                <p>🔄 데이터 선택이 해제되었습니다.</p>
                <span>새로운 데이터를 검색하고 싶으시면 원하는 키워드를 입력해주세요.</span>
                <small>예: '서울시 교통 데이터', '부산 관광 정보' 등</small>
            </ContextResetMessage>
          ) : message.type === "utilization-dashboard" ? (
            <UtilizationDashboard
              data={message.data}
              fileName={message.fileName}
              onCategorySelect={onCategorySelect}
            />
          ) : message.type === "data_detail" ? (
            <>
              <DataDetailView data={message.data} />
              <DetailHint>
                  <p>💡 이 데이터를 어떻게 활용하고 싶으신가요? 자유롭게 질문해주세요!</p>
                  <strong>예시:</strong>
                  <ul>
                    <li>"전체 활용" - 모든 활용방안 대시보드 🔍</li>
                    <li>"해외 사례와 연관 지어 활용"</li>
                    <li>"[특정 목적]을 위한 활용" - 예: "마케팅 전략 수립을 위한 활용"</li>
                  </ul>
              </DetailHint>
            </>
          ) : message.type === "help" ? (
            <HelpMessage />
          ) : message.type === "error" ? (
            <ErrorMessage>{message.text}</ErrorMessage>
          ) : (
            <>
              {message.type === "simple_recommendation" && message.recommendations ? (
                <RecommendationList>
                  {message.recommendations.map((rec, index) => (
                    <RecommendationItem key={index}>
                      <ReactMarkdown>{rec}</ReactMarkdown>
                    </RecommendationItem>
                  ))}
                </RecommendationList>
              ) : (
                <MessageText>
                  <ReactMarkdown>{message.text || ''}</ReactMarkdown>
                </MessageText>
              )}
              {message.type === "simple_recommendation" && (
                <TipMessage>
                  💡 다른 데이터 조회를 원하시면 '다른 데이터 활용'을 입력하시고, 다른 활용방안을 원하시면 프롬프트를 작성해주세요.
                </TipMessage>
              )}
            </>
          )}
        </MessageItem>
      ))}

      {isTyping && (
        <MessageItem sender="bot">
          <TypingIndicator>
            <Spinner />
            <span>입력 중...</span>
          </TypingIndicator>
        </MessageItem>
      )}

      <div ref={messageEndRef} />
    </MessageListContainer>
  );
}

// ============== Styled Components ==============

const spin = keyframes`
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
`;

const TypingIndicator = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem;
`;

const Spinner = styled.div`
  width: 18px;
  height: 18px;
  border: 3px solid rgba(0, 0, 0, 0.1);
  border-top-color: #888; 
  border-radius: 50%;
  animation: ${spin} 1s linear infinite;
`;

const MessageListContainer = styled.div`
  flex-grow: 1;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 15px;
  position: relative;
`;

const MessageItem = styled.div`
  padding: ${(props) =>
    props.type === 'search_results' || props.type === 'search_not_found' || props.type === 'context_reset' || props.type === 'data_detail' || props.type === 'help' || props.children?.props?.data ? "0" : "10px 15px"};
  border-radius: 20px;
  max-width: ${(props) =>
    props.type === 'search_results' || props.type === 'search_not_found' || props.type === 'context_reset' || props.type === 'data_detail' || props.type === 'help' || props.children?.props?.data ? "95%" : "70%"};
  word-wrap: break-word;
  white-space: pre-wrap;
  background-color: ${(props) => {
    if (props.type === 'search_results' || props.type === 'search_not_found' || props.type === 'context_reset' || props.type === 'data_detail' || props.type === 'help') return `transparent`;
    if (props.children?.props?.data) return `background: transparent; padding: 0; box-shadow: none;`;
    return props.sender === "user" ? "#0099ffff" : "#e9e9eb";
  }};
  color: ${(props) => (props.sender === "user" ? "white" : "black")};
  align-self: ${(props) =>
    props.sender === "user" ? "flex-end" : "flex-start"};

  ${(props) =>
    props.children?.props?.data &&
    `
    background: none;
    padding: 0;
    border-radius: 0;
  `}
`;

const MessageText = styled.div`
  line-height: 1.5;
  text-align: left;
  p { margin: 0; }
  strong { font-weight: 600; color: #000000ff; }
  h3 { font-size: 1.2em; margin: 0; padding-bottom: 10px; border-bottom: 1px solid #bcbcbcff; }
  hr { display: none; }
  p > strong { margin-right: 3px; }
  ul { padding-left: 20px; margin: 0; }
  li { margin-bottom: 0px; }
  blockquote { margin: 0; padding: 0 15px; background-color: #f7f9fc; border-left: 4px solid #0099ffff; border-radius: 0 8px 8px 0; color: #4a5568; }
`;

const ContextResetMessage = styled.div`
  padding: 12px 18px;
  border: 1px solid #e0e7ff;
  background-color: #fafbff;
  border-radius: 15px;
  text-align: center;
  width: 100%;
  max-width: 100%;
  align-self: center;
  p { font-weight: 600; font-size: 1.05em; color: #374151; margin: 0 0 8px 0; }
  span { font-size: 0.95em; color: #6b7280; display: block; margin-bottom: 10px; }
  small { font-size: 0.9em; color: #9ca3af; }
`;

const RecommendationList = styled.div`
  display: flex;
  flex-direction: column;
  gap: 10px;
  text-align: left;
`;

const RecommendationItem = styled.div`
  background-color: #f8f9fa;
  padding: 10px 15px;
  border-radius: 10px;
  border: 1px solid #e9ecef;
  line-height: 1.5;
  p { margin: 0; }
`;

const TipMessage = styled.div`
  margin-top: 12px;
  padding: 10px 15px;
  background-color: #f0f7ff;
  border-radius: 15px;
  font-size: 0.9em;
  color: #4a5568;
  line-height: 1.5;
  text-align: left;
`;

const DetailHint = styled.div`
  margin-top: 12px;
  padding: 10px 15px;
  background-color: #f0f7ff;
  border-radius: 15px;
  font-size: 0.9em;
  color: #4a5568;
  line-height: 1.5;
  text-align: left;
  p { margin: 0 0 8px 0; font-weight: 500; }
  strong { font-weight: 600; }
  ul { list-style-type: '• '; padding-left: 1.2em; margin: 5px 0 0 0; }
  li { margin-bottom: 4px; }
`;

const ErrorMessage = styled.div`
    background-color: #fff0f0;
    color: #c53030;
    padding: 10px 15px;
    border-radius: 15px;
    border: 1px solid #fdb8b8;
`;

const HelpContainer = styled.div`
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  padding: 20px;
  h4 { font-size: 1.2em; color: #111827; margin: 0 0 12px 0; }
  p { color: #374151; margin: 0 0 16px 0; line-height: 1.6; }
`;

const HelpList = styled.ul`
  list-style: none;
  padding: 0;
  margin: 0;
  li { background: #ffffff; border: 1px solid #e5e7eb; padding: 12px; border-radius: 8px; margin-bottom: 8px; font-size: 0.95em; color: #4b5563; strong { color: #1f2937; } }
`;

const DetailContainer = styled.div`
  background: white;
  border-radius: 16px;
  border: 1px solid #e2e8f0;
  padding: 20px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  h3 { font-size: 1.4em; color: #1a202c; margin-top: 0; margin-bottom: 16px; border-bottom: 2px solid #f1f5f9; padding-bottom: 12px; }
`;

const DetailGrid = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
`;

const DetailItem = styled.div`
  font-size: 0.95em;
  color: #4a5568;
  strong { color: #2d3748; }
`;

const KeywordSection = styled.div`
  margin-top: 16px;
  strong { display: block; margin-bottom: 8px; color: #2d3748; }
`;

const KeywordContainer = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
`;

const KeywordTag = styled.span`
  background-color: #edf2f7;
  color: #4a5568;
  padding: 4px 10px;
  border-radius: 16px;
  font-size: 0.9em;
`;

const DescriptionSection = styled.div`
  margin-top: 16px;
  strong { display: block; margin-bottom: 8px; color: #2d3748; }
  blockquote { margin: 0; padding: 12px; background-color: #f7fafc; border-left: 4px solid #e2e8f0; color: #4a5568; white-space: pre-wrap; line-height: 1.6; }
`;

const SearchResultsContainer = styled.div`
  background: white;
  border-radius: 16px;
  border: 1px solid #e2e8f0;
  padding: 20px;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  h4 { font-size: 1.2em; color: #111827; margin: 0 0 16px 0; }
`;

const ResultsList = styled.ol`
  list-style: none;
  padding: 0;
  margin: 0 0 16px 0;
  counter-reset: result-counter;
`;

const ResultItem = styled.li`
  counter-increment: result-counter;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  padding: 12px;
  border-radius: 8px;
  margin-bottom: 8px;
  font-size: 0.95em;
  color: #374151;
  &::before {
    content: counter(result-counter) ". ";
    font-weight: 600;
    color: #0099ffff;
    margin-right: 8px;
  }
`;

const SearchNotFoundContainer = styled.div`
  background-color: #fffbeb;
  border: 1px solid #fef3c7;
  border-radius: 16px;
  padding: 20px;
  text-align: center;
  h4 { font-size: 1.2em; color: #b45309; margin: 0 0 8px 0; }
  p { color: #92400e; margin: 0 0 10px 0; }
  strong { color: #b45309; }
`;

export default MessageList;
